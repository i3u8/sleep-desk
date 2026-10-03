# 导出录音客观分析

## 用途与边界

`tools/analyze_export.py` 只在本机分析 ZIP 元数据与录音，不上传、不调用模型、不进行人工听辨或修改分类器。原事件类型只作分组字段，不是真值。没有标注和完整录音，不能计算准确率、召回率、漏检率，不能声称新分类提高准确率，也不能推断疾病或真实睡眠状态。

脚本直接读取 ZIP 成员，将 m4a 字节交给 ffmpeg stdin 解码；不解压 ZIP，不以成员名构造输出路径。ffmpeg 仅允许 pipe 协议，限制输入成员大小、解码时长和执行时间。解码失败会记录在报告并返回非零状态，不把缺失音频伪装为成功。输出目录必须位于本仓库之外。

## 复现

从仓库根目录运行，Node/Android 构建不是依赖：

```powershell
uv run --python 3.12 --with numpy python tools/analyze_export.py --self-test
uv run --python 3.12 --with numpy python tools/analyze_export.py C:/Users/unknown/Downloads/sleep-desk-1a0f818b-20261002_0132.zip --output-dir C:/Users/unknown/Documents/ChatGPT/健康/private-analysis/sleep-desk-1a0f818b --pcm-dir C:/Users/unknown/Documents/ChatGPT/健康/private-analysis/sleep-desk-1a0f818b/pcm
```

依赖 Python 3.12、NumPy、PATH 中的 ffmpeg。未安装大型模型。`--pcm-dir` 可省略。

输出文件：

| 文件 | 内容 |
| --- | --- |
| `report.md` | 中文聚合报告与限制 |
| `aggregate.json` | 聚合统计、屏幕事件、各范围特征分布 |
| `analysis.json` | 源 ZIP SHA-256、工具版本、逐片特征、逐事件数据和重复峰值近邻 |
| `clips.csv` | 展平的逐片客观特征 |
| `events.csv` | 去除导出别名重复后的事件、持续时间、片段覆盖、屏幕上下文 |
| `duplicate-peak-pairs.csv` | 按起点排序的相邻声学事件中，同峰值且间隔不超过 2 秒的事件对 |
| `pcm/manifest.tsv` | 可选 PCM 回放清单 |
| `pcm/0001-<hash>.s16le` 等 | 可选 16 kHz、单声道、有符号 16 位 little-endian 原始 PCM，无 WAV 头 |

PCM 文件名仅含顺序号与录音内容 SHA-256 前缀，不使用 ZIP 路径。PCM 和事件元数据仍是敏感数据，应保留在仓库外；不要提交或公开分享。

### Kotlin 回放约定

manifest 的前四列为 `path,event_type,duration_ms,screen_context`，以 TAB 分隔。`path` 为绝对路径，`duration_ms` 是导出 `endMs-timeMs`，不是强制至少 150 ms 的分类时长。其余列包括：

- `screen_context_known`：有无此前屏幕事件。
- `screen_context`：`true/false/unknown`。以事件结束墙钟时刻为近似，最近屏幕状态为 ON 或最近切换在 30 秒内则为 true；并非精确恢复当时 listener 状态。
- `sample_rate,channels,samples,decoded_duration_ms`：PCM 格式和实际解码长度。
- `candidate_tail_start_sample`：由事件墙钟时长从解码末尾反推的估计起点。
- `candidate_pre2s_start_sample`：机械跳过前 32000 样本的另一探索范围。
- `candidate_offsets_are_estimates`：始终 true。任何推定范围都不是旧引擎真实 sample offset。
- `member,event_id`：关联原导出；`event_type` 不得当评测真值。

建议 Kotlin 回放分别报告整片、两个推定范围的特征及输出分布，而不是把新旧分类差异称为准确率变化。本脚本没有执行 Kotlin 分类器。

## 统计口径

`events` 和 `audioEvents` 在本导出完全相同，各 2155 条。脚本优先读取 `events`，仅在该键不存在时回退 `audioEvents`，记录两者是否一致，不相加；同会话重复事件 ID 会报错。声学事件由显式的七种音频枚举区分，屏幕与充电事件不参与声学时长分桶。

“同峰值近邻”限定同一会话、相邻声学事件、起点差 ≤2000 ms、peakLevel 差 ≤1e-6 dB，不等同重复音频。导出峰值可能受同一上下文影响；脚本另记录压缩音频 SHA-256 重复数量。屏幕 ±30 秒统计按事件起点计算，独立于 manifest 的结束时刻上下文。

片段覆盖按事件引用、代表片段引用、ZIP 实际成员三层核对。片段时长相加可能重叠；以事件结束时刻向前放置片段所得并集仅为估计，不能当麦克风整夜可用率。导出器只收集分段代表片段，因此 ZIP 缺少事件引用的音频不等于设备丢失录音。

## 声学方法

- 解码为 16 kHz mono float，计算 RMS/峰值 dBFS、峰均比、过零率、接近满幅比例、10 ms RMS 包络 CV 和 P90–P10 dB 跨度。
- 旧谱基线：整片中点单个 512 样本 Hann 窗，即 32 ms。复现旧版频带规则：80–300、(300,1500]、(1500,6000] Hz；NumPy double FFT 与 Kotlin float FFT 不保证逐位相同。
- 多窗：512 样本 Hann、256 步长，包含首尾窗口，短片段补零；先聚合功率，再计算频带比例、质心、谱平坦度、窗口归一谱 L1 变化。
- 比较旧中点→整片多窗、整片多窗→尾部估计 candidate、多窗尾部→pre2s。前三频带比例 L1 距离范围 0–2，不是错误率。尾部起点另做 ±50/100 ms 敏感性检查，但不代表覆盖全部边界不确定性。
- 稳定性以包络 CV≤0.25 作探索阈值，不识别具体噪声来源。周期性采用去均值包络在 0.3–3 秒的自相关，按完整包络能量归一化，要求至少容纳两周期；不足为 null。搜索边界峰单独计数，周期性≥0.35 不代表鼾声。

旧源码取约 2 秒 pre-roll，6 秒 ring 在候选结束时立即 slice，不等待未来 post-roll。分类时长被钳制到至少 150 ms，但导出仍写原始事件墙钟起止，因此 `<150 ms` 与源码不矛盾。旧中点窗可能落在 pre-roll 内，说明分类特征有上下文污染风险。

**无法精确重建 candidate**：旧 ZIP 没有采样偏移，墙钟与样本时钟可能有差异；AAC 编码延迟/补零影响解码边界；ring 可能截去前文。尾部与 pre2s 都仅是探索模型，不能把任一模型当真实候选区域，尤其不能据此断言听到了什么。

## 2026-10-02 本地结果

这次 ZIP 为 1 个会话、88 个分段、129 个 m4a，全部解码成功。会话时长 27461.222 秒（约 7.628 小时），2155 条事件中声学事件 2150 条。

| 原标签 | 事件数 | 导出片段数 |
| --- | ---: | ---: |
| NIGHT_WAKE_SOUND | 1870 | 73 |
| SPEECH | 242 | 47 |
| COUGH | 23 | 4 |
| SNORE | 11 | 2 |
| ABNORMAL | 4 | 3 |

另外 SCREEN_ON/OFF 各 2 条、CHARGING_ON 1 条。声学事件 `<150 ms` 112 条、`<300 ms` 233 条、`<1000 ms` 743 条、`≥6000 ms` 4 条；这些桶相互重叠。重复峰值近邻 58 对，涉及 110 个事件；21 个声学起点在屏幕切换 ±30 秒内。

事件引用 1099 个不同录音路径，其中 970 个未随代表片段导出；129 个代表引用全部存在。导出片段约覆盖 6.00% 的声学事件，而不是整夜声音的随机样本。解码总长 381.504 秒；尾部对齐估计并集 372.629 秒，约会话的 1.36%，不代表麦克风可用率。

探索性尾部模型中，118/129 个旧中点窗整体早于候选估计起点。旧中点与尾部频带 L1 中位数 0.3966，≥0.5 有 53 个；旧中点→全片多窗中位数 0.3448，全片多窗→尾部为 0.1333。尾部与 pre2s 中位数差 0.0127。这说明窗口及取样范围对特征有明显影响，不说明哪一种分类一定正确。

整片/尾部/pre2s 包络 CV≤0.25 分别 0/31/21 个；可估周期分别 129/67/72 个，自相关≥0.35 分别 27/2/2 个。短候选可观测周期不足，不能把这一下降解释为周期声减少或准确率改善。

### 生产 Kotlin 规则回放

使用 `tools/ReplayAudio.kt` 对 manifest 的 129 条 PCM 分别运行整片、尾部估算、跳过前两秒估算，共 387 次。直接编译调用生产 `FeatureExtractor` 与 `RuleClassifier`，不是 Python 翻译版分类器。输出 `kotlin-replay.tsv` 留在仓库外。

| audio-v1.3 输出 | 整片（含上下文） | 尾部估算 | 跳过前两秒 |
| --- | ---: | ---: | ---: |
| ENV_NOISE | 111 | 100 | 104 |
| FALSE_TRIGGER | 0 | 10 | 0 |
| SPEECH | 14 | 14 | 18 |
| NIGHT_WAKE_SOUND | 4 | 3 | 4 |
| COUGH | 0 | 2 | 3 |
| SNORE | 0 | 0 | 0 |
| ABNORMAL | 0 | 0 | 0 |

这不是准确率表，也没有重放能量门控。旧导出缺少背景噪声值，回放明确不启用需要该证据的异常声判断与相对背景拒识。屏幕上下文为导出事件近似恢复。新周期规则没有在这些代表片段里产生鼾声，可能存在召回损失，必须通过人工标注和后续完整候选特征确认，不能据此说整晚没有鼾声。`ENV_NOISE` 包含未获得充分类别证据的环境活动，不等于确认声音来源是风扇或空调。

编译 `ReplayAudio.kt` 时将 `AudioFeatures.kt`、`NightModels.kt`、`RuleClassifier.kt` 一起编入 JVM classpath；入口为 `ReplayAudioKt <manifest.tsv> <output.tsv>`。原录音和逐条分类只保留在本机，不进入提交。

## 验证与命令故障

内置 `--self-test` 检查合成低频前文与高频候选的分离、短片段和静音、已知 0.5 秒调制周期、区间并集及路径拒绝。实际 ZIP 验证覆盖全部 129 个片段；本任务未修改交互行为，无 UI E2E 变更。

实际执行曾有四条 `Get-Content` 失败：默认工作目录为仓库父目录，相对路径找不到。根因是并行读取时遗漏 `workdir`；已用显式仓库目录重试成功，后续固定 `workdir`。未找到项目 `agent-incidents` 工具，且本任务只允许两个仓库文件，因此未写其他日志或修改 `AGENTS.md`。
