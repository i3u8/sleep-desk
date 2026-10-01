# sleep-desk 麦克风夜间事件算法方案（v1 可接入）

> **目标**：在手机通常不在身边、陀螺仪/加速度不可靠的前提下，用麦克风作为主信号，在普通安卓机上轻量检测夜间声学事件；**不存整夜原始音频**，仅在关键事件时落盘短片段 + 元数据索引。  
> **对接面**：挂在现有 `SleepTrackingService`（前台服务）生命周期上，与 `SessionStore` 会话一并启停。  
> **版本策略**：先规则/轻量特征可上线（v1），Tiny TFLite 分类留作二期。  
> **实测对策（2026-10）**：用户反馈「漏检偏多；手机离床远时几乎听不到」→ **默认切远场/高灵敏度**（见 §2.4）。主应用请把 `SleepTrackingService` 里 `audioEngine.start(session.id, AudioAlgoConfig())` 换成 **`AudioAlgoConfig.farField()`**（或把 data class 默认值直接改为远场档）。
> **段式展示 / 聚合**：见 [`docs/segments.md`](segments.md)（检测层仍产出细粒度事件；UI/统计用 Segment + 代表 clip 配额）。

---

## 0. 与现有 App 的挂载关系

| 现有组件 | 现状 | 音频接入建议 |
| --- | --- | --- |
| `SleepTrackingService` | FGS + 加速度 60s 能量桶；`specialUse` | 会话 `start`/`stop` 时同步调用 `NightAudioEngine.start/stop`；通知文案可提示「麦克风监测中」 |
| `SessionStore` | `filesDir/sleep_session.json` 存 motion buckets | 扩展或并列 `audio_events` 索引（JSON/SQLite）；片段文件放 `filesDir/audio_clips/{sessionId}/` |
| `MainActivity` | Start/Stop + 通知权限 | 增加 `RECORD_AUDIO` 运行时请求；未授权则降级为仅运动（与 README「No mic」兼容开关） |
| Manifest | `FOREGROUND_SERVICE` + `SPECIAL_USE`，targetSdk 34 | 麦克风路径需额外：`RECORD_AUDIO`、`FOREGROUND_SERVICE_MICROPHONE`，service type 改为 `specialUse\|microphone`（或仅 `microphone`，按产品合规选择） |

**原则**：麦克风管线是独立引擎，由 FGS 拥有；运动能量可作**辅证**（夜醒打分），不作主信号。

---

## 1. 事件类型定义

### 1.1 枚举（v1）

| 枚举值 | 中文 | 声学直觉 | v1 判定侧重 | 默认是否存片段 |
| --- | --- | --- | --- | --- |
| `SNORE` | 鼾声 | 周期约 2–8 s 的呼吸脉冲，能量集中在约 80–800 Hz，谐波/自相关峰明显 | 周期性 + 频带能量比 + 持续多周期 | 是（可合并为「鼾段」节流） |
| `COUGH` | 咳嗽 | 短时冲击（~50–400 ms），宽带能量，高过零率突变 | 短冲击 + 谱质心偏高 | 是 |
| `SPEECH` | 说话 | 人声基频与共振峰结构；夜间多为短句/呓语 | 人声频带持续 +（可选）轻量 VAD | 是（隐私敏感，默认开，可设置关） |
| `NIGHT_WAKE_SOUND` | 夜醒相关声 | 床铺摩擦、脚步、开关门、坐起衣物声等非周期突发 | 非周期突发 + 能量明显高于噪声地板 + 可选屏幕/充电辅证 | 是 |
| `ENV_NOISE` | 环境噪 | 风扇、空调、窗外交通等稳态或缓变 | 高能量但平稳、无事件结构 | **否**（只记统计） |
| `ABNORMAL` | 异常噪 | 尖锐报警、持续尖叫/犬吠、玻璃碎裂等 | 高谱质心 + 极高突发或持续超阈 | 是 |
| `FALSE_TRIGGER` | 误报/丢弃 | 门限误触、手指碰机、通知提示音等 | 分类置信低 / 冷却期内重复 / 时长过短 | **否**（可 debug 记 log） |

### 1.2 会话级衍生指标（不单独成「采集事件」）

- `snore_minutes`：合并后的鼾声分钟数  
- `cough_count` / `speech_count` / `wake_sound_count`  
- `noise_floor_dbfs_p50`：整夜噪声地板中位数（用于次日校准）  
- `audio_coverage_pct`：麦克风实际可用时长占比（被系统抢麦/错误时下降）

### 1.3 与运动/非运动辅信号的融合（可选）

| 辅信号 | 用途 | 注意 |
| --- | --- | --- |
| 加速度高能量桶（现有） | 抬高 `NIGHT_WAKE_SOUND` 置信；单独运动高而音频静 → 可能远距/无麦 | 手机不在身边时运动不可靠，**禁止**单独当夜醒金标准 |
| 屏幕亮灭（`ACTION_SCREEN_ON/OFF`） | 亮屏附近 ±30 s 声学突发 → 倾向夜醒/互动 | 勿存屏幕内容 |
| 充电插拔 | 起床相关弱先验 | OEM 差异大 |
| UsageStats（可选、需授权） | 判断是否有人真正在用手机 | 权限重，v1 可不做 |

---

## 2. 采集参数（推荐默认 + 调参）

> **默认档位**：主应用会话应以 **§2.4 远场/高灵敏度** 为默认；下表 §2.1 为近场/床头基线（对照与回退）。常量名对齐 `NightModels.kt` 的 `AudioAlgoConfig`。

### 2.1 近场/床头基线参数表（对照用）

| 参数 | 近场基线 | 可选范围 | 理由 |
| --- | --- | --- | --- |
| 采样率 | **16000 Hz** | 8000 / 16000 / 44100 | 16 kHz 覆盖鼾声主能量与咳嗽冲击；算力约为 44.1 kHz 的 ~1/3 |
| 声道 | **mono** | mono | 床边单麦足够；减半带宽与编码体积 |
| 编码 PCM | **16-bit** (`ENCODING_PCM_16BIT`) | float 内部可转 | `AudioRecord` 最稳妥；特征用 float 归一化 |
| AudioSource | **`UNPROCESSED`**（`preferUnprocessedSource=true`） | fallback `MIC` | 系统 AGC/降噪易抹掉低电平鼾声细节 |
| AGC | **关** | 极安静房间可弱开 | 夜间相对门限已自适应；AGC 会扭曲能量地板 |
| 系统降噪 / NS | **默认关** | 嘈杂城市窗边可试 | 可能削谐波结构，伤害鼾声周期检测 |
| `windowMs` | **30** | 20–40 | 兼顾时间分辨率与谱稳定 |
| `hopMs` | **15** | 10–20 | 50% 重叠，CPU 可接受 |
| `energySmoothMs` | **200** | 100–400 | 抑瞬时尖峰误触发 |
| `floorPercentile` / `floorWindowMs` | **p20 @ ~45 s** | — | **相对门限**，避免固定 dBFS |
| `marginDb` | **10f** | 6–15 | 灵敏度主旋钮；误报多则加大 |
| `minCandidateMs` | **250** | 150–400 | 滤除极短碰触 |
| `maxCandidateMs` | **8000** | 6–10 s | 与片段上限对齐 |
| `snoreMergeMs` | **8000** | — | 抑重复打点 |
| `preRollMs` / `postRollMs` | **1500** | 1–2 s | 触发时能取 pre-roll |
| `confidenceFloor` | **0.55f** | 0.35–0.65 | 低于此不落盘 |

### 2.2 AudioRecord 草参

```text
sampleRate     = 16000
channelConfig  = CHANNEL_IN_MONO
audioFormat    = ENCODING_PCM_16BIT
bufferSize     = max(AudioRecord.getMinBufferSize(...), 16000)  // ≥1s 更抗调度抖动
audioSource    = UNPROCESSED if available else MIC
```

### 2.3 算力与耗电粗估（中端机）

| 阶段 | 大致负载 | 说明 |
| --- | --- | --- |
| 持续 PCM 读入 + RMS/ZCR | ~1–3% 单核 | 每 hop 一次标量特征 |
| 候选时短时 FFT（256/512） | 偶发 +2–5% | 仅门限触发后算谱特征 |
| AAC 编码短片段 | 偶发尖峰 | 每夜若 50–200 次事件，总编码时间仍很小 |
| 整夜不存 raw | I/O 极低 | 对比连续写 WAV，耗电与磨损差一个数量级 |

**耗电建议**：

1. 使用已有 `PARTIAL_WAKE_LOCK`（服务已持有），避免额外高频 wake。  
2. 特征线程用单后台线程 + 有界队列，勿每帧新建线程。  
3. 屏灭时保持 `AudioRecord` 连续读（断续启停反而更耗且易丢事件）；若需极致省电，可做「静音 5 分钟后降 hop 到 30 ms」——v1 可不做。  
4. 通知必须 ongoing；Android 14+ 麦克风 FGS 需用户从前台启动会话（现有 Start 按钮路径已满足）。


### 2.4 高灵敏度 / 远场预设（实测漏检对策）

**背景（用户实测）**：漏检偏多；手机放离床较远时事件几乎听不到。远场路径损失可达十余 dB，弱鼾/衣物摩擦能量贴近噪声地板；近场门限（`marginDb=10`、`minCandidateMs=250`、`confidenceFloor=0.55`）会系统性漏检。

**策略**：优先 **漏检↓**，接受误报略升；用相对门限 + 软件增益，**不要**开系统 AGC。

主应用请把 `SleepTrackingService` 中：

```kotlin
audioEngine.start(session.id, AudioAlgoConfig())
```

改为：

```kotlin
audioEngine.start(session.id, AudioAlgoConfig.farField())
// 或：把 AudioAlgoConfig 的 data class 默认值直接改成下表「远场」列
```

#### 2.4.1 近场 vs 远场对照表

| 参数 / 常量 | 近场/床头（旧默认） | **远场/高灵敏度（新默认推荐）** | 声学理由 |
| --- | --- | --- | --- |
| `marginDb` | `10f` | **`6f`**（极远可试 `5f`；误报多再升到 `7–8f`） | **远场主旋钮**：路径损失后峰–地差距变小，10 dB 裕量过高 |
| `sensitivity` | `1.0f`（或无） | **`1.25f`**（配合 `effectiveMarginDb()`） | 有效裕量 ≈ `marginDb/sensitivity`；现有字段可立刻加压 |
| `minCandidateMs` | `250` | **`180`**（可试 `150`） | 远场能量更弱、片段更碎，250 ms 易在门限边缘抖掉 |
| `hopMs` | `15` | **`10`（可选）** 或保持 `15` | 更密采样能量包络；CPU 略升。不确定时先保持 15 |
| `windowMs` | `30` | **保持 `30`**（稳谱可试 `40`） | **推荐保持 30**：冲击/咳嗽时间分辨率更好；谱噪大再升 40 |
| `floorPercentile` | `0.20f`（p20） | **`0.15f`–`0.20f`（推荐 p15）** | 分位略低 → 地板偏低 → 更易过门限；估偏高会**全漏** |
| `floorWindowMs` | `45_000` | **`60_000`–`90_000`（推荐 60s）** | 更长窗让地板更稳 |
| 冷启动 `noiseFloor` 初值 | `-50f`（旧值） | **`-58f`（v0.2.3）** | 初值过高 → 开场几分钟远场全漏；**仅 idle 更新** |
| `confidenceFloor` | `0.55f` | **`0.40f`–`0.45f`（推荐 `0.42f`）** | 弱事件规则分偏低，0.55 会在落盘前丢掉 |
| `preRollMs` / `postRollMs` | `1500` | **`2000`** | 弱事件边界糊，多留上下文 |
| `snoreMergeMs` | `8000` | **`10000`**（可 8–12 s） | 略增减少碎鼾事件 |
| `energySmoothMs` | `200` | **`300–350`** | 更长 EMA 抬高远处弱突发 |
| `preferUnprocessedSource` | `true` | **仍 `true`** | 远场优先 UNPROCESSED；电平极低再 fallback |
| AudioSource fallback | — | **MIC**；慎用 VOICE_RECOGNITION | 见 `fallbackMicIfLowGain`；注明 **AGC 风险** |
| `digitalGainDb` | 无 | **+6~+9 dB**（极远 +12，需 clip） | **建议新增**：特征前固定数字增益，替代系统 AGC |
| RuleClassifier `periodicity` | `>= 0.45` | **`>= 0.35` 左右**（可 0.32–0.35） | 远场自相关峰变弱；硬编码在 `RuleClassifier.kt` |

#### 2.4.2 主应用应改常量清单

| 常量名 | 近场 | 远场推荐 | 文件/位置 | 状态 |
| --- | --- | --- | --- | --- |
| `marginDb` | `10f` | **`6f`** | `NightModels.kt` → `AudioAlgoConfig` | **现有字段立刻可改** |
| `sensitivity` | `1.0f` | **`1.25f`** | 同上；引擎用 `effectiveMarginDb()` | **现有字段立刻可改** |
| `minCandidateMs` | `250` | **`180`** | 同上 | **现有字段立刻可改** |
| `hopMs` | `15` | **`10`（可选）或 `15`** | 同上 | **现有字段立刻可改** |
| `windowMs` | `30` | **`30`**（或 `40`） | 同上 | **现有字段立刻可改** |
| `confidenceFloor` | `0.55f` | **`0.42f`** | 同上 | **现有字段立刻可改** |
| `preRollMs` | `1500` | **`2000`** | 同上 | **现有字段立刻可改** |
| `postRollMs` | `1500` | **`2000`** | 同上 | **现有字段立刻可改** |
| `snoreMergeMs` | `8000` | **`10000`** | 同上 | **现有字段立刻可改** |
| `floorPercentile` | `0.20f` | **`0.15f`** | 同上（代码名是 `floorPercentile`，不是 `noiseFloorPercentile`） | **现有字段立刻可改** |
| `floorWindowMs` | `45_000` | **`60_000`** | 同上（代码名是 `floorWindowMs`，不是 `noiseFloorWindowMs`） | **现有字段立刻可改** |
| `energySmoothMs` | `200` | **`350`** | 同上 | **现有字段立刻可改** |
| `preferUnprocessedSource` | `true` | **`true`** | 同上 | 保持 |
| `fallbackMicIfLowGain` | `false`/无 | **`true`** | 同上 | **现有字段立刻可改** |
| `lowGainDbThreshold` | — | **`-52f`** | 同上 | **现有字段立刻可改** |
| 冷启动 `noiseFloor` 初值 | `-50f` | **`-58f`** | `NightAudioEngineImpl` 启动赋值 | **v0.2.3 已更新** |
| SNORE `periodicity` 门限 | `0.45f` | **`0.35f`** | `RuleClassifier.kt`（**非** AudioAlgoConfig） | **硬编码立刻可改**；建议抽到 `snorePeriodicityMin` |
| SNORE `durationMs` 下限 | `~1200` | **`800–1000`** | `RuleClassifier.kt` | **硬编码立刻可改** |
| `digitalGainDb` | — | **`+6f`** | `AudioAlgoConfig`；能量/特征/AAC 前 soft-clip | **v0.2.3 已实现** |
| `snorePeriodicityMin` | — | **`0.35f`** | 建议加入 `AudioAlgoConfig`，供 RuleClassifier 读 | **建议新增** |

#### 2.4.3 AudioSource 与软件增益（远场必读）

1. **优先** `preferUnprocessedSource=true` → `UNPROCESSED`（无系统 AGC/NS，保留低电平细节）。  
2. 若 UNPROCESSED **电平极低**（idle 中位数长期低于约 `-52 dBFS`）：fallback 试 `MIC`；再不行可试 `VOICE_RECOGNITION`，但须知 **AGC 会抬高/压低能量、扭曲 `noiseFloor`**，调 `marginDb` 时要重新标定。现有：`fallbackMicIfLowGain` / `lowGainDbThreshold` / `lowGainProbeMs`。  
3. **不要**依赖系统 AGC；在能量、特征与 AAC 编码前对 PCM 做固定 **数字增益**（`digitalGainDb=+6f`，写入前 soft-clip 防削波）。也可按 `noiseFloor` 自适应目标电平。

#### 2.4.4 噪声地板：估偏高 = 远场全漏

- 实现：idle hop 的低分位；窗口长度 ≈ `floorWindowMs / hopMs` 个 hop（`NightAudioEngineImpl.updateNoiseFloor`）。  
- 远场推荐：`floorPercentile=0.15f`，`floorWindowMs=60_000`（可试 90 s）。  
- **仅 idle 更新**（候选/事件段不更新），避免事件污染抬高地板。  
- 冷启动初值已设为 `-58f` 而非 `-50`：开场地板过高会导致「永远不触发」。

调低灵敏度（误报过多时）：增大 `marginDb`（6→7–8）或把 `sensitivity` 调到 `1.0` / `0.85`；勿用系统 AGC 硬扛。

---

## 3. 特征与检测管线

### 3.1 总览

```text
AudioRecord (16 kHz mono)
    → 环形 PCM 缓冲 (≈4–6 s)
    → 每 hop: RMS / dBFS、ZCR、（可选）子带能量
    → 噪声地板自适应
    → 能量门限：相对地板超阈且持续 ≥250 ms → 候选段
    → 候选段提特征：谱质心、频带比、自相关周期、冲击度
    → 规则分类器 → NightEvent
    → 误报压制（冷却、合并、辅信号、置信阈值）
    → 命中「关键」→ 截取 pre/post → AAC 编码落盘 + 写索引
```

### 3.2 阶段 A：噪声地板与能量门

对每帧 RMS 转 dBFS（相对 full-scale）：

\[
\mathrm{dBFS} = 20\log_{10}(\mathrm{RMS} + \epsilon)
\]

- 维护滑动窗（`floorWindowMs`：近场 ~45 s / **远场 60–90 s**）的低分位（`floorPercentile`：近场 p20 / **远场 p15–p20**）作为 `noiseFloor`。  
- 仅在「非候选、非事件段」更新地板，避免事件污染。  
- 冷启动初值已设为 **-58 dBFS**（勿卡死在 -50）。  
- 触发条件：`smoothedDb > noiseFloor + effectiveMarginDb()` 连续 `N` 个 hop（近场 ≥250 ms / **远场 ≥180 ms**，见 `minCandidateMs`）。  
- `effectiveMarginDb = marginDb / sensitivity`（远场默认 `marginDb=6`, `sensitivity=1.25` → ≈4.8 dB）。  
- **警告**：噪声地板估偏高 → 远场几乎全漏（见 §2.4.4）。

**为何不用固定 dB**：床边距离、机型麦灵敏度、空调噪声差异巨大；相对门限是 v1 稳定性的关键。

### 3.3 阶段 B：候选段边界

- **起点**：门限连续满足。  
- **终点**：回落到 `noiseFloor + margin×0.45` 持续 ≥100 ms，或达到 `maxCandidateMs`（8 s）强制切段。  
- 从环形缓冲取出：`[t_start - preRoll, t_end + postRoll]`，总长 cap 到 `maxClipMs`（默认 8 s）。

### 3.4 阶段 C：轻量特征（规则分类输入）

| 特征 | 计算要点 | 主要服务类别 |
| --- | --- | --- |
| `rmsDb` / `peakDb` | 段内统计 | 门控、异常 |
| `zcrMean` / `zcrStd` | 过零率 | 咳嗽/摩擦 vs 低频轰鸣 |
| `bandLow` (80–300 Hz) | FFT 或简易 IIR 能量 | 鼾声、环境低频 |
| `bandMid` (300–1500 Hz) | 同上 | 鼾声谐波、人声 |
| `bandHigh` (1500–6000 Hz) | 同上 | 咳嗽、尖噪 |
| `spectralCentroid` | 加权频率均值 | 尖噪 vs 闷响 |
| `periodSec` / `periodicity` | 自相关峰（0.3–3.0 s 搜索） | **鼾声核心** |
| `attackMs` | 能量从 10%→90% 时间 | 冲击（咳）vs 缓起（空调变速） |
| `durationMs` | 段长 | 说话/鼾段/咳 |

**鼾声规则（硬编码于 `RuleClassifier.kt`，非 `AudioAlgoConfig`；远场须放宽）：**

```text
# 近场偏严（旧文档/旧常量）
periodicity >= 0.45
AND periodSec in [0.4, 3.0]
AND bandLow+bandMid 占优（high 能量比 < 0.35）
AND durationMs >= 1200
→ SNORE

# 远场/高灵敏度推荐（应改 RuleClassifier 内常量）
periodicity >= 0.35   # 可 0.32–0.35；远场自相关峰变弱
AND periodSec in [0.35, 3.5]
AND bandHigh < 0.42
AND durationMs >= 800–1000
→ SNORE
```

> 长期应抽到 `AudioAlgoConfig.snorePeriodicityMin`（**建议新增**）再由分类器读取。

**咳嗽：**

```text
durationMs in [80, 600]
AND attackMs < 80
AND centroid 偏高 或 bandHigh 相对高
→ COUGH
```

**说话（粗）：**

```text
durationMs in [400, 8000]
AND mid 带持续
AND 无明显长周期（periodicity < 0.35）
→ SPEECH
（可选：android-vad / WebRTC VAD 作人声确认，见 §6）
```

**环境噪：**

```text
高能量但 attack 缓、periodicity 低、duration 很长且方差小
→ ENV_NOISE（不存片段，只更新统计）
```

其余落入 `NIGHT_WAKE_SOUND` 或 `ABNORMAL`（由 peak/centroid 阈值区分）。

### 3.5 误报压制策略

1. **相对门限 + 地板污染防护**（见上）。  
2. **最短时长 / 最短周期数**：鼾至少 ~1–2 个周期；咳过短尖峰需重复确认或丢弃。  
3. **同类合并冷却**：`SNORE` 在 `snoreMergeMs` 内合并为同一 `snore_bout`（近场 8 s / **远场推荐 10 s**），只打一个索引点或更新 `endMs`。  
4. **会话头尾静默**：开始后 30 s、结束前用户操作窗口，降低拿手机声误报。  
5. **通知音黑名单（弱）**：若事件紧贴本 App 通知更新且极短，标 `FALSE_TRIGGER`（不可靠则仅作日志）。  
6. **置信度阈值**：规则打分 0–1，近场 `<0.55`、**远场 `<0.40–0.45`（`confidenceFloor`，推荐 0.42）** 不落盘（可仍记 debug 计数）。  
7. **每小时事件上限**：如每类 120 次，超出只记计数不存音频，防磁盘与隐私膨胀。  
8. **辅信号否决**：屏幕亮且 Usage 显示前台交互 → `SPEECH`/`WAKE` 置信↑；纯稳态高噪 → 强制 `ENV_NOISE`。

### 3.6 二期（非 v1 阻塞）：Tiny TFLite

- 输入：候选段 log-mel（或直接 0.975 s 波形块）。  
- 模型：自研小 CNN / 蒸馏头；或 YAMNet embedding + 小分类头（见 §6 取舍）。  
- 输出：与枚举对齐的 softmax；与规则分数融合（`0.4*rule + 0.6*model`）。

---

## 4. 「关键片段」保存策略

### 4.1 触发条件（满足其一且通过误报压制）

| 类型 | 存片段 | 说明 |
| --- | --- | --- |
| `SNORE` | 是 | 同一 bout 内节流：每 bout 最多存 **首段 + 每 N 分钟采样一段**（默认 N=5） |
| `COUGH` | 是 | 每次达标事件 |
| `SPEECH` | 是（可设置关闭） | 隐私默认开但设置页可关「保存说话片段」 |
| `NIGHT_WAKE_SOUND` | 是 | 建议与运动高能量或屏幕亮辅证叠加时优先存 |
| `ABNORMAL` | 是 | 必存 |
| `ENV_NOISE` / `FALSE_TRIGGER` | 否 | 仅元数据/计数 |

### 4.2 时长与编码

| 项 | 推荐默认 |
| --- | --- |
| pre-roll | 近场 **1.5 s** / 远场 **`preRollMs=2000`** |
| post-roll | 近场 **1.5 s** / 远场 **`postRollMs=2000`** |
| 单文件总长上限 | **8 s**（硬顶 10 s） |
| 容器 | **M4A（AAC-LC）** via `MediaCodec` + `MediaMuxer` |
| AAC 码率 | **32–48 kbps** mono |
| Opus | Android 有软编支持（较新系统/`c2.android.opus.encoder`），但 **机型碎片与 mux 到 Ogg/WebM 成本更高**；**v1 推荐 AAC/M4A** 兼容性最好 |
| 不存 | 整夜 PCM/WAV、未触发时段 |

### 4.3 目录与命名

```text
filesDir/
  audio_clips/
    {sessionId}/
      20261001T233045_SNORE_a1b2c3.m4a
      20261001T014412_COUGH_d4e5f6.m4a
  audio_index.json   # 或 Room/SQLite 表 audio_events
```

- `sessionId`：可用 `Session.startMs` 的十六进制或 ISO 日+序号。  
- 文件名：`{UTC或本地紧凑时间}_{TYPE}_{短随机}.m4a`（文档与实现统一用设备本地时区即可，索引里存 epoch ms）。

### 4.4 索引字段（JSON 数组元素 / 表行）

```json
{
  "id": "a1b2c3",
  "sessionStartMs": 1727712000000,
  "type": "SNORE",
  "startMs": 1727742645000,
  "endMs": 1727742652000,
  "confidence": 0.78,
  "noiseFloorDb": -48.2,
  "peakDb": -22.5,
  "periodSec": 1.6,
  "periodicity": 0.62,
  "clipPath": "audio_clips/1727712000000/20261001T233045_SNORE_a1b2c3.m4a",
  "clipDurationMs": 7000,
  "preRollMs": 1500,
  "postRollMs": 1500,
  "aux": {
    "screenOnNearby": false,
    "motionBucketHigh": false
  },
  "algoVersion": "audio-v1"
}
```

**隐私**：

- 默认仅本地；备份/分享需显式用户动作。  
- 设置项：「保存说话片段」「保留天数」（默认 14 天，启动时清理）。  
- 结束会话时可展示「本会话保存了 N 段共 X MB」。

---

## 5. 可落地的 v1 接口草图（Kotlin）

> 以下为**接口与伪代码**，不修改现有业务源码；主应用按此接入即可。

### 5.1 数据模型

```kotlin
package com.i3u8.sleepdesk.audio

enum class NightEventType {
    SNORE, COUGH, SPEECH, NIGHT_WAKE_SOUND, ENV_NOISE, ABNORMAL, FALSE_TRIGGER
}

data class NightEvent(
    val id: String,
    val type: NightEventType,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val clipRelativePath: String?, // null = 未存片段
    val features: Map<String, Float> = emptyMap(),
    val algoVersion: String = "audio-v1.2"
)

/**
 * 默认值用远场档；或保留近场默认 + companion.farField()。
 * SleepTrackingService: audioEngine.start(session.id, AudioAlgoConfig.farField())
 */
data class AudioAlgoConfig(
    val sampleRate: Int = 16_000,
    val windowMs: Int = 30,                 // 远场可保持 30；稳谱可试 40
    val hopMs: Int = 10,                    // 远场推荐 10；近场可用 15
    val marginDb: Float = 6f,               // 远场主旋钮；近场曾用 10f
    val sensitivity: Float = 1.25f,         // 现有：effectiveMargin ≈ marginDb/sensitivity
    val preRollMs: Int = 2_000,             // 远场略增弱事件上下文
    val postRollMs: Int = 2_000,
    val maxClipMs: Int = 8_000,
    val minCandidateMs: Int = 180,          // 远场；近场曾用 250
    val maxCandidateMs: Int = 8_000,
    val saveSpeechClips: Boolean = true,
    val snoreClipIntervalMs: Long = 5 * 60_000L,
    val snoreMergeMs: Long = 10_000L,       // 远场略增，减碎事件
    val preferUnprocessedSource: Boolean = true,
    val fallbackMicIfLowGain: Boolean = true,
    val lowGainDbThreshold: Float = -52f,
    val lowGainProbeMs: Int = 3_500,
    val energySmoothMs: Int = 350,
    val floorWindowMs: Int = 60_000,        // 远场 60–90s；近场曾 ~45s
    val floorPercentile: Float = 0.15f,     // 远场 p15；近场曾 p20
    val confidenceFloor: Float = 0.42f,     // 远场 0.40–0.45；近场曾 0.55
    val aacBitrate: Int = 40_000,
    val sessionWarmupMs: Long = 12_000L,
    // —— 建议新增（代码尚无则按此名补字段）——
    val digitalGainDb: Float = 6f,              // 能量、特征与 AAC 前固定数字增益，soft-clip 防削波
    // val snorePeriodicityMin: Float = 0.35f, // 供 RuleClassifier 读取，替代硬编码
) {
    fun effectiveMarginDb(): Float =
        (marginDb / sensitivity.coerceAtLeast(0.5f)).coerceIn(3f, 18f)

    companion object {
        /** 床头近场（漏检可接受、误报更少） */
        fun nearField() = AudioAlgoConfig(
            hopMs = 15,
            marginDb = 10f,
            sensitivity = 1.0f,
            preRollMs = 1_500,
            postRollMs = 1_500,
            minCandidateMs = 250,
            snoreMergeMs = 8_000L,
            energySmoothMs = 200,
            floorWindowMs = 45_000,
            floorPercentile = 0.20f,
            confidenceFloor = 0.55f,
            fallbackMicIfLowGain = false,
        )

        /** 远场/高灵敏度 —— 主应用会话默认应使用本工厂 */
        fun farField() = AudioAlgoConfig() // 与 data class 远场默认一致
    }
}
```

**字段状态**：上表中未注释字段对齐 `NightModels.kt` 现有名（`floorWindowMs` / `floorPercentile` 等）。`snorePeriodicityMin` 仍为建议项；`digitalGainDb=6f` 已在 v0.2.3 实现，并在能量、特征和 AAC 编码前做 soft-clip。冷启动 `noiseFloor` 初值已在 `NightAudioEngineImpl` 设为 `-58f`。

### 5.2 引擎接口

```kotlin
interface NightAudioListener {
    fun onEvent(event: NightEvent)
    fun onNoiseFloor(dbfs: Float) {}
    fun onEngineError(t: Throwable) {}
}

interface NightAudioEngine {
    fun start(sessionId: String, config: AudioAlgoConfig = AudioAlgoConfig())
    fun stop()
    fun isRunning(): Boolean
    fun setListener(listener: NightAudioListener?)
    /** 可选：由服务注入辅信号 */
    fun onAuxScreenChanged(isOn: Boolean) {}
    fun onAuxMotionHigh(bucketStartMs: Long, energy: Double) {}
}
```

### 5.3 管线伪代码

```kotlin
class NightAudioEngineImpl(
    private val appContext: Context,
    private val clipStore: AudioClipStore // 负责 AAC 编码与索引写入
) : NightAudioEngine {

    @Volatile private var running = false
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val ring = PcmRingBuffer(capacitySamples = 16_000 * 6) // 6s
    private var listener: NightAudioListener? = null
    private var config = AudioAlgoConfig()
    private var sessionId: String = ""

    override fun start(sessionId: String, config: AudioAlgoConfig) {
        if (running) return
        this.sessionId = sessionId
        this.config = config
        // 1) 选 AudioSource：UNPROCESSED → MIC
        // 2) 创建 AudioRecord，startRecording()
        // 3) 启动 recordThread：循环 read → ring → processHop
        running = true
    }

    override fun stop() {
        running = false
        recordThread?.join(500)
        audioRecord?.release()
        audioRecord = null
        // flush 未完成候选（可选丢弃）
    }

    override fun isRunning() = running
    override fun setListener(listener: NightAudioListener?) { this.listener = listener }

    private fun processHop(frame: ShortArray, hopStartMs: Long) {
        val rmsDb = calcRmsDb(frame)
        updateNoiseFloorIfIdle(rmsDb)
        val over = rmsDb > noiseFloor + config.marginDb
        updateCandidateState(over, hopStartMs, frame)

        if (candidateJustEnded) {
            val pcm = ring.slice(candidateStartMs - config.preRollMs, candidateEndMs + config.postRollMs)
                .takeMs(config.maxClipMs)
            val feats = extractFeatures(pcm)
            val (type, conf) = classifyRules(feats)
            if (type == NightEventType.FALSE_TRIGGER || conf < config.confidenceFloor) return
            if (type == NightEventType.ENV_NOISE) {
                listener?.onEvent(NightEvent(/* clipPath=null */ ...)); return
            }
            if (shouldThrottleSnore(type)) return

            val path = if (shouldSaveClip(type)) clipStore.encodeAacAndIndex(
                sessionId, type, pcm, feats, conf
            ) else null
            listener?.onEvent(NightEvent(id = ..., type, ..., clipRelativePath = path, ...))
        }
    }
}
```

### 5.4 挂到 `SleepTrackingService` 的接入示意

```kotlin
// 在 SleepTrackingService 内（示意，非本任务改码）：
// onCreate: audioEngine = NightAudioEngineImpl(this, AudioClipStore(this))
// ensureSession / startSensors 同时:
//   audioEngine.setListener { ev -> store.appendAudioEvent(ev) }
//   audioEngine.start(sessionId = ..., config = AudioAlgoConfig.farField())  // 勿再用近场默认
// finishAndStop:
//   audioEngine.stop()
//
// startForeground 使用类型：
//   FOREGROUND_SERVICE_TYPE_SPECIAL_USE or FOREGROUND_SERVICE_TYPE_MICROPHONE
//   （API 34+ 用 ServiceCompat.startForeground(..., type)）
```

### 5.5 权限与 Manifest 检查清单

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<!-- 若保留加速度床边采样，可继续 SPECIAL_USE，并用 | 合并 type -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".SleepTrackingService"
    android:exported="false"
    android:foregroundServiceType="microphone|specialUse">
    ...
</service>
```

- **运行时**：`RECORD_AUDIO` 必须在用户点「开始睡」时（App 可见）已授予，再 `startForegroundService`。  
- **通知文案**：需如实披露麦克风用途（应用商店与 Google Play 政策敏感）。  
- **targetSdk 34**：缺少 `FOREGROUND_SERVICE_MICROPHONE` 或 type 会导致 `SecurityException`。

### 5.6 `SessionStore` 扩展建议（索引）

- 短期：在现有 JSON 增加 `audioEvents: [ ... ]`（注意整夜写入频率，可批量 flush 每 10–30 s）。  
- 更好：`Room`/`SQLite` 表 `audio_events` + 文件路径；会话结束再汇总到 UI。

---

## 6. 开源参考与取舍（已核实，勿盲目整库拷贝）

| 项目 | 链接 | 许可（据公开信息） | 适合端侧？ | 对本方案建议 |
| --- | --- | --- | --- | --- |
| **YAMNet**（TensorFlow models / AudioSet） | https://github.com/tensorflow/models/tree/master/research/audioset/yamnet | Apache-2.0（代码）；模型权重按仓库说明使用 | 有官方/社区 TFLite 路径，可端侧，但 **~521 类 + MobileNet** 相对「整夜常开」偏重 | **二期**作 embedding/迁移学习；v1 **不直接常驻跑全量 YAMNet**。类目含 Snoring 等，但仍需场景微调，误报需自建阈值 |
| **TFLite Audio Classification Codelab** | https://developers.google.com/codelabs/tflite-audio-classification-basic-android | 文档/示例遵循 Google 条款 | 演示 mic → YAMNet 实时分类 | 可作集成参考；非鼾声专用、耗电需实测 |
| **WebRTC VAD** | https://webrtc.googlesource.com/src/+/main/common_audio/vad/ （BSD 风格） | BSD | 极轻量 | **为人声活动设计**，对鼾声/床响 **不敏感或易误判**；仅可辅助 `SPEECH`，**不能当鼾声检测器** |
| **android-vad**（gkonovalov） | https://github.com/gkonovalov/android-vad | MIT | 封装 WebRTC / Silero / Yamnet VAD，端侧友好 | 可选用 WebRTC 子模块做说话辅助；注意包体与 API level |
| **FOSS Snore Detector**（elgrande73） | https://github.com/elgrande73/FOSS-snore-detector-android-app | 宣称 FOSS / F-Droid 导向（接入前核对仓库 LICENSE） | 纯 on-device DSP：RMS、ZCR、100Hz–1kHz 频带等 + FGS | **思路高度可参考**（特征与前台服务）；算法与 UI 勿整仓复制，按本 App 会话模型重写 |
| **SnoringDemo / YAMnet RN 示例** | https://github.com/pkaypilania/SnoringDemo 等 | 各仓库自有 LICENSE | 展示 16 kHz float PCM + 窗推理 | 架构参考；技术栈为 RN，不能直接依赖 |
| **Android AudioRecord / MediaCodec** | https://developer.android.com/reference/android/media/AudioRecord · MediaCodec | 平台 API | 一等公民 | **v1 采集与 AAC 编码正路径**；Opus 编码存在但碎片多，v1 优先 AAC |

### 6.1 明确「不可直接用」的原因摘要

1. **WebRTC VAD ≠ 鼾声检测**：频带与模型为人声优化。  
2. **全量 YAMNet 整夜推理**：功耗、发热、以及 AudioSet 标签与床边场景分布不匹配 → 需门控后短时推理或蒸馏小模型。  
3. **带系统 NS/AGC 的 VOICE_ 源**：可能破坏低电平周期结构。  
4. **闭源商业睡眠 SDK**：与本 App MIT/本地优先策略冲突时勿强绑。

---

## 7. 调参建议（落地一周内）

> **先确认档位**：远场漏检场景应已切 `AudioAlgoConfig.farField()`（§2.4）。下表突出 **远场漏检路径**。

| 现象 | 优先调什么 |
| --- | --- |
| **远场几乎无事件 / 漏检偏多** | ① `marginDb` ↓（10→**6**，极远试 **5**）② `confidenceFloor` ↓→**0.40–0.45** ③ `minCandidateMs` ↓→**180** ④ 查 `noiseFloor` 是否偏高（`floorPercentile`↓、`floorWindowMs`↑、冷启动初值 **-55/-60**）⑤ 加 `digitalGainDb` +6~+9（建议新增）⑥ `RuleClassifier` 放宽 `periodicity`→**0.35** ⑦ 确认 UNPROCESSED 电平；过低开 `fallbackMicIfLowGain` |
| 整夜事件爆炸（误报↑） | `marginDb` ↑（远场 6→**7–8**，勿直接跳回 10 除非近场）；`confidenceFloor` 略↑；`minCandidateMs` ↑；`snoreMergeMs` ↑ |
| 漏检明显鼾声（近场） | `marginDb` ↓；确认 `UNPROCESSED`；检查是否被 NS 削波；放宽 `RuleClassifier` 的 `periodicity` |
| 空调被当成鼾 | 提高对「长周期+低方差」→ `ENV_NOISE` 的权重；要求 ≥2 个稳定自相关峰 |
| 说话片段过多 | 设置关闭 `saveSpeechClips`；或提高 SPEECH 置信阈值 |
| 耗电偏高 | 确认无 44.1 kHz；`hopMs` 若已改 10 可退回 15；FFT 仅候选段；避免主线程读写 JSON |
| 杀进程丢麦 | 检查 FGS type/权限与厂商电池白名单；通知渠道勿被关 |
| UNPROCESSED 电平极低 | `fallbackMicIfLowGain=true`；试 MIC；慎用 VOICE_RECOGNITION（AGC 风险）；或软件 `digitalGainDb` |

**远场漏检快速路径（按优先级）**：

```text
marginDb 过大? → floor 估偏高/初值-50? → confidenceFloor 过高?
→ minCandidateMs 过长? → periodicity 0.45 过严? → 无数字增益且源电平过低?
→ 仍漏：hopMs=10 + digitalGainDb=+9 + marginDb=5
```

**自测清单**：静室基线 10 分钟 → 播放公开鼾声样本（**手机故意放远**）→ 咳嗽/说话 → 开风扇；对比索引类型分布与片段可听性；同时 log `noiseFloor` / `smoothedDb` / `effectiveMarginDb()`。

---

## 8. 实施里程碑（主应用）

1. **M0**：权限 + Manifest `microphone` + 空引擎只打 RMS 到 log。  
2. **M1**：能量门 + 候选 + 规则分类 + 索引（无编码）。  
3. **M2**：AAC 片段落盘 + 节流 + 设置页隐私开关。  
4. **M3**：与 motion bucket / 屏幕辅融合；UI 展示事件时间线。  
5. **M4（可选）**：候选段 Tiny TFLite。

---

## 9. 默认参数速查（复制用）

**远场 / 高灵敏度（主应用默认，`AudioAlgoConfig.farField()`）**

```text
SR=16000 mono PCM16 | win=30ms hop=10ms（可保持15）
source=UNPROCESSED→MIC fallback | AGC=off | NS=off | digitalGainDb=+6（soft-clip，系统 AGC 不额外启用）
noiseFloor=p15@60s（floorPercentile=0.15, floorWindowMs=60000）| 冷启动初值=-58
marginDb=6 | sensitivity=1.25 | confidenceFloor=0.42
pre/post=2.0s | maxClip=8s | AAC-LC 40kbps m4a
minCandidate=180ms | snoreMergeMs=10s | snore re-clip every 5min
RuleClassifier: periodicity>=0.35 | SNORE durationMs>=800~1000
SleepTrackingService: start(..., AudioAlgoConfig.farField())
algoVersion=audio-v1.2
```

**近场 / 床头对照（`AudioAlgoConfig.nearField()`）**

```text
win=30ms hop=15ms | marginDb=10 | confidenceFloor=0.55
noiseFloor=p20@45s | pre/post=1.5s | minCandidate=250ms | snoreMergeMs=8s
```

---

**可交给主应用接入**
