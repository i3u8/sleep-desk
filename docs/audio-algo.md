# sleep-desk 麦克风夜间事件算法方案（v1 可接入）

> **目标**：在手机通常不在身边、陀螺仪/加速度不可靠的前提下，用麦克风作为主信号，在普通安卓机上轻量检测夜间声学事件；**不存整夜原始音频**，仅在关键事件时落盘短片段 + 元数据索引。  
> **对接面**：挂在现有 `SleepTrackingService`（前台服务）生命周期上，与 `SessionStore` 会话一并启停。  
> **版本策略**：先规则/轻量特征可上线（v1），Tiny TFLite 分类留作二期。

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

### 2.1 推荐默认参数表

| 参数 | 推荐默认 | 可选范围 | 理由 |
| --- | --- | --- | --- |
| 采样率 | **16000 Hz** | 8000 / 16000 / 44100 | 16 kHz 覆盖鼾声主能量与咳嗽冲击；算力约为 44.1 kHz 的 ~1/3 |
| 声道 | **mono** | mono | 床边单麦足够；减半带宽与编码体积 |
| 编码 PCM | **16-bit** (`ENCODING_PCM_16BIT`) | float 内部可转 | `AudioRecord` 最稳妥；特征用 float 归一化 |
| AudioSource | **先 `UNPROCESSED`，低增益则回退 `MIC`** | 仅 `MIC` | 远距离时 UNPROCESSED 无 AGC 可能过弱；引擎探测 idle 中位数 < −52 dBFS 后自动切 MIC |
| AGC | **关**（不强依赖系统 AGC） | 极安静房间可弱开 | 夜间相对门限已自适应；AGC 会扭曲能量地板 |
| 系统降噪 / NS | **默认关** | 嘈杂城市窗边可试 | 可能削谐波结构，伤害鼾声周期检测 |
| 分析窗长 | **30 ms**（480 samples @16k） | 20–40 ms | 兼顾时间分辨率与谱稳定 |
| 跳步 hop | **15 ms**（240 samples） | 10–20 ms | 50% 重叠，CPU 可接受 |
| 能量平滑 | **350 ms EMA**（远距默认） | 100–400 ms | 更长积分抬高远处弱事件 |
| 噪声地板更新 | 近 **60 s** 低分位（**p15**） | p10–p25 / 30–90 s | 自动噪声底；仅 idle 更新 |
| 门限裕量 | 噪声地板 + **~4.8–6 dB**（默认高灵敏度） | 3–12 dB | `marginDb/sensitivity`；宁可多假阳少漏检 |
| 候选最短时长 | **150 ms** | 80–400 ms | 放宽以减少漏检 |
| 候选最长（单段） | **8 s**（再切段） | 6–10 s | 与片段上限对齐 |
| 分类冷却 | 同类事件 **3–8 s** 合并 | — | 抑重复打点 |
| 环形缓冲 | **前后各 1.5 s** PCM 常驻 | 1–2 s | 触发时能取 pre-roll |

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


### 2.4 远距离 / 高灵敏度（v0.2.2）

手机常在床头柜/充电器上，声源距离 1–3 m。实现侧默认：

1. **先开 `UNPROCESSED`**；约 3.5 s idle 探测若能量中位数 < −52 dBFS，**自动回退 `MIC`**（系统增益帮远距）。  
2. **更长能量 EMA（350 ms）** + **更低相对门限**（`sensitivity` 默认 1.25）。  
3. **噪声底 p15 / 60 s**，会话前 ~12 s warmup 不报事件。  
4. 规则分类器放宽周期/时长/频带门槛，`confidenceFloor≈0.42`，宁可多记 `NIGHT_WAKE_SOUND` 也不要漏鼾/突发。  
5. UI：事件写入后立刻广播；首页 Snackbar「检测到：…」+ 列表实时刷新。

调低灵敏度：增大 `AudioAlgoConfig.marginDb` 或把 `sensitivity` 调到 `1.0` / `0.85`。

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

- 维护滑动窗（如 45 s）的 **p20** 作为 `noiseFloor`。  
- 仅在「非候选、非事件段」更新地板，避免事件污染。  
- 触发条件：`smoothedDb > noiseFloor + effectiveMarginDb` 连续 `N` 个 hop（默认约 ≥75–150 ms，高灵敏度）。  
- `effectiveMarginDb = marginDb / sensitivity`（默认 `marginDb=6`, `sensitivity=1.25` → ≈4.8 dB）。

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

**鼾声 v1 规则示例（可调）：**

```text
periodicity >= 0.45
AND periodSec in [0.4, 3.0]
AND bandLow+bandMid 占优（high 能量比 < 0.35）
AND durationMs >= 1200（至少一个呼吸周期量级）
→ SNORE
```

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
3. **同类合并冷却**：`SNORE` 在 8 s 内合并为同一 `snore_bout`，只打一个索引点或更新 `endMs`。  
4. **会话头尾静默**：开始后 30 s、结束前用户操作窗口，降低拿手机声误报。  
5. **通知音黑名单（弱）**：若事件紧贴本 App 通知更新且极短，标 `FALSE_TRIGGER`（不可靠则仅作日志）。  
6. **置信度阈值**：规则打分 0–1，`<0.55` 不落盘（可仍记 debug 计数）。  
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
| pre-roll | **1.5 s** |
| post-roll | **1.5 s** |
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
    val algoVersion: String = "audio-v1"
)

data class AudioAlgoConfig(
    val sampleRate: Int = 16_000,
    val windowMs: Int = 30,
    val hopMs: Int = 15,
    val marginDb: Float = 10f,
    val preRollMs: Int = 1_500,
    val postRollMs: Int = 1_500,
    val maxClipMs: Int = 8_000,
    val saveSpeechClips: Boolean = true,
    val snoreClipIntervalMs: Long = 5 * 60_000L,
    val preferUnprocessedSource: Boolean = true
)
```

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
            if (type == NightEventType.FALSE_TRIGGER || conf < 0.55f) return
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
//   audioEngine.start(sessionId = store.loadCurrent()!!.startMs.toString())
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

| 现象 | 优先调什么 |
| --- | --- |
| 整夜事件爆炸 | `marginDb` ↑（如 10→13）；提高最短时长；加强合并冷却 |
| 漏检明显鼾声 | `marginDb` ↓；确认 `UNPROCESSED`；检查是否被 NS 削波；放宽 `periodicity` |
| 空调被当成鼾 | 提高对「长周期+低方差」→ `ENV_NOISE` 的权重；要求 ≥2 个稳定自相关峰 |
| 说话片段过多 | 设置关闭 `saveSpeechClips`；或提高 SPEECH 置信阈值 |
| 耗电偏高 | 确认无 44.1 kHz；FFT 仅候选段；避免主线程读写 JSON |
| 杀进程丢麦 | 检查 FGS type/权限与厂商电池白名单；通知渠道勿被关 |

**自测清单**：静室基线 10 分钟 → 播放公开鼾声样本 → 咳嗽/说话 → 开风扇；对比索引类型分布与片段可听性。

---

## 8. 实施里程碑（主应用）

1. **M0**：权限 + Manifest `microphone` + 空引擎只打 RMS 到 log。  
2. **M1**：能量门 + 候选 + 规则分类 + 索引（无编码）。  
3. **M2**：AAC 片段落盘 + 节流 + 设置页隐私开关。  
4. **M3**：与 motion bucket / 屏幕辅融合；UI 展示事件时间线。  
5. **M4（可选）**：候选段 Tiny TFLite。

---

## 9. 默认参数速查（复制用）

```text
SR=16000 mono PCM16 | win=30ms hop=15ms
source=UNPROCESSED|MIC | AGC=off | NS=off
noiseFloor=p20@45s | margin=10dB
pre/post=1.5s | maxClip=8s | AAC-LC 40kbps m4a
minCandidate=250ms | snore cooldown merge=8s | snore re-clip every 5min
algoVersion=audio-v1
```

---

**可交给主应用接入**
