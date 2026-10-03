# 标注录音的特征对照实验

## 结论

本轮确实运行了原始录音对照，不是只列备选算法。**值得继续开发的是预训练声音模型的多类别语义分数和时间上下文，而不是继续提高或降低单一频带/慢周期阈值。** 但没有得到足够稳定、可以直接上线的分类器。

- 手工细频谱与时变特征比粗频谱参照有更多信息，但组合仍把床板和部分背景错当鼾声。
- 本机 YAMNet 原声的 8 类分数、各取均值和最大值，共 16 维，是本轮最有希望的表示。不能简化成“Snoring 分数超过 0.5 就是鼾声”。
- 原声留一录音对照曾达到 10/11，但下降 6 dB 后只有 4/11；这推翻了“已经足够可靠”的判断。
- 同源分组增广后原声 9/11、下降 6 dB 后 6/11，仍不达交付标准。未将任何模型或新阈值写入 APK。
- K 的用户疑虑只保留为人工复核标记；未训练、评估或输出呼吸危险程度。

## 样本与协议

12 个用户标注来自同一晚、同一设备，且是有目的地挑选的代表/相似片段，不是随机测试集。排除需要上下文的 C，主要实验是 **6 个鼾声 vs 5 个非鼾声，共 11 个独立原音**。不具备验证床板、说话、接触声各自泛化的条件，因为这些子类各只有一个样本。

1. 使用原始 M4A，逐条检查 SHA-256，再在本机解码为 16 kHz 单声道；不用人工试听时放大的 WAV。
2. 所有派生片段、音量变体按原音 SHA-256 分组。测试某条时，其所有变体均不在训练中。
3. 手工特征对照固定 `StandardScaler + class_weight=balanced + LogisticRegression(C=0.1)`。scaler 只在该折训练数据拟合，不搜索超参数。
4. 逐条留出；同时保留第一轮 7 条确定标签到第二轮 4 条的迁移检查。第二轮标签已见过，不能称盲测。
5. 分别测试原声、降低音量、前/后裁剪 100 ms 和估计候选尾部。尾部没有人工精确边界，只用于敏感性检查。
6. 分组增广的五种变体为原声、±6 dB、前/后裁 100 ms。增广权重每源合计 1，之后仍叠加类别平衡权重；不是把 55 个变体算成 55 个独立样本。
7. 挑选特征组、前景对照、预训练对照、增广策略是逐步探索。最终选择已利用这些开发结果，不能把最高得分当作无偏的上线准确率。

## 手工特征

各组独立与同一个简单分类器比较；**“粗频谱参照”不是旧 APK 本身的准确率**。下表使用修复音高提取后重跑的 v4 结果。

| 表示 | 维数 | 留出原音判断一致数 | 鼾声找回 | 非鼾声排除 |
| --- | ---: | ---: | ---: | ---: |
| 粗频谱、时长、包络等参照 | 10 | 4/11 | 4/6 | 0/5 |
| 冲击与包络调制 | 16 | 6/11 | 5/6 | 1/5 |
| 细频带及谱形分布 | 22 | 7/11 | 5/6 | 2/5 |
| 频谱动态 | 10 | 6/11 | 5/6 | 1/5 |
| MFCC 均值、离散度、变化量 | 36 | 5/11 | 4/6 | 1/5 |
| 短时谐波结构 | 7 | 6/11 | 4/6 | 2/5 |
| 包络＋谱形＋动态 | 48 | 6/11 | 4/6 | 2/5 |
| 上述新特征组合 | 91 | 8/11 | 6/6 | 2/5 |
| 相对前段背景的谱增量 | 20 | 6/11 | 5/6 | 1/5 |
| 谱增量＋短时谐波结构 | 27 | 6/11 | 5/6 | 1/5 |

当前样本上的最佳手工组合仍错 A、G、J。不是“堆更多特征就能稳定识别”。同一晚只有很少样本，高维特征也有过拟合风险。

具体测量：

- **谱形**：80–6000 Hz 分成 6 个频带；短时质心、带宽、平坦度、熵和 rolloff 的中位数及四分位差。
- **动态**：相邻帧谱差、质心变化，10/30/100 ms 间隔的谱相似度。此处保留变化统计，不将整段压成三个频带。
- **包络**：峰均比、上升/下降、峰密度、脉冲性，以及约 0.5–90 Hz 的包络调制能量；快纹理与几秒一次的呼吸节律分开。
- **MFCC**：12 个非能量倒谱系数的均值、标准差、相邻变化。不使用整体音量系数作为类别捷径。
- **谐波结构**：64 ms 帧内归一化自相关强度、相关阈值通过比例、YIN 估计频率及变化；保留原始帧索引，不跨静音计算变化。阈值通过率不是真正发声概率，不是逐周期 jitter，更不是呼吸风险指标。
- **相对背景**：将最初 `min(1.5秒, 半片长度)` 作为参考，比较新增频谱及持续性。前段不保证安静，背景中可能含前一个事件，所以这个假设也需要验证。

## 预训练表示

只下载公共模型，在本机 CPU 推理；未上传录音。源为 TensorFlow 官方 `tflite-support` 仓库，固定提交 `522a02b47444e6016d5a0d4d1388b522bc2529f2` 的 `yamnet_tfhub.tflite`。

- 模型 16,096,668 字节，SHA-256：`141fba1cdaae842c816f28edc4937e8b4f0af4c8df21862ccc6b52dc567993c3`。
- 运行时：`ai-edge-litert==2.2.0`；输入 waveform，输出 521 类分数、1024 维 embedding 和 log-mel 特征。
- 16 维表示：Snoring、Breathing、Speech、Creak、Sigh、Cough、Silence、Noise，分别取帧分数均值和最大值。仍使用同一固定的折内线性分类器。
- embedding 的均值/最大值对照使用归一化余弦最近邻，未在 11 段数据上训练 1024 维分类头；不能与线性分类器结果解释成完全相同条件的单因素实验。

| 表示 | 原声留出判断一致数 | 鼾声找回 | 非鼾声排除 |
| --- | ---: | ---: | ---: |
| 原声 16 维类别分数 | 10/11 | 5/6 | 5/5 |
| 原声 embedding 均值 | 7/11 | 4/6 | 3/5 |
| 原声 embedding 最大值 | 9/11 | 6/6 | 3/5 |
| RMS 调整后 16 维类别分数 | 5/11 | 3/6 | 2/5 |
| RMS 调整后 embedding 均值 | 6/11 | 4/6 | 2/5 |
| RMS 调整后 embedding 最大值 | 5/11 | 3/6 | 2/5 |

不能把第一个结果理解为“YAMNet 原生分类 10/11”：这是其多类分数作为输入，再用剩余录音的二分类标签拟合得到的结果。原模型 Snoring 最大分数会把 D 的说话评得较高（约 0.66），却给 H 的鼾声很低分（约 0.05）；单分数阈值仍不可靠。

## 稳健性否决

前两列都以未见过该原音的模型测试；扰动版本从不混入该条的训练折。裁剪可能改变原标签覆盖的内容，因此裁剪只作敏感性检查，音量测试更直接。

| 测试输入 | 仅原声训练 | 同源分组增广训练 |
| --- | ---: | ---: |
| 原声 | 10/11 | 9/11 |
| 降低 6 dB | 4/11 | 6/11 |
| 提高约 6 dB（峰值保护） | 8/11 | 8/11 |
| 去掉开头 100 ms | 7/11 | 8/11 |
| 去掉结尾 100 ms | 9/11 | 10/11 |

增广版原声错 D/H；降低 6 dB 后鼾声仅找到 2/6。B/F/G/I/J 在五种条件中均正确，其他存在错判或翻转。C 完全不参与训练与计分，但增广模型在原声等多数条件下仍把它当作鼾声，进一步说明“呼吸/叹气/鼾声”的边界尚未解决。

**因此本轮不发布新 APK，不固化这些分数阈值，不宣称医学识别能力。** 数据规模和扰动失败已足够否决直接上线，不能用“还有一组数据是 10/11”掩盖失败。

## 后续实施方向

1. 以多类别语义表示为主候选，保留短窗序列和真实前后文，再做事件级融合；不只用全片均值或单一 Snoring 最大分数。
2. 声音类别与活动/风险语义分离：床板不等于醒来、Breathing 不等于异常、Snoring 分数不代表危险程度。
3. 加入音量/设备/位置变化的校准和分组测试；本轮的简单 RMS 归一化没有改善，不作为默认上线方案。
4. 继续保留手工谱形与动态作为可解释诊断指标，不能凭单个床板或说话样本设硬阈值。
5. 截断信息与未知类别显式保留。新录制流程需要真实 post-roll；旧录音缺失的后文不能通过补零恢复。
6. 在新的独立录音上确认方案，再决定端侧集成。当前无需让用户继续逐条标注已有整晚数据。

## 复现

只在本机执行；输出路径必须在仓库外。依赖固定在 `tools/audio-study-requirements.txt`。

```powershell
uv run --python 3.12 --with-requirements tools/audio-study-requirements.txt python tools/audio_feature_study.py --self-test
uv run --python 3.12 --with-requirements tools/audio-study-requirements.txt python tools/audio_feature_study.py --annotations ../private-analysis/human-annotations-v1.json --clips-csv ../private-analysis/sleep-desk-1a0f818b/clips.csv --output-dir ../private-analysis/feature-study-v4
uv run --python 3.12 --with-requirements tools/audio-study-requirements.txt --with ai-edge-litert==2.2.0 python tools/yamnet_feature_study.py --annotations ../private-analysis/human-annotations-v1.json --model C:/Users/unknown/.cache/sleepdesk-audio-models/yamnet.tflite --labels C:/Users/unknown/.cache/sleepdesk-audio-models/yamnet-labels.txt --output-dir ../private-analysis/feature-study-v3
uv run --python 3.12 --with-requirements tools/audio-study-requirements.txt python tools/evaluate_grouped_audio_features.py --annotations ../private-analysis/human-annotations-v1.json --vectors ../private-analysis/feature-study-v3/yamnet-vectors.npz --output-dir ../private-analysis/feature-study-v3
```

模型固定源路径：TensorFlow `tflite-support` 上述提交中的 `tensorflow_lite_support/metadata/python/tests/testdata/audio_classifier/yamnet_tfhub.tflite`；标签同目录 `yamnet_521_labels.txt`。下载后运行脚本会验证模型 SHA-256。不要用原目录中测试 metadata JSON 的示例参数代替 YAMNet 实际 16 kHz 输入规范。

私有产物：

- `feature-study-v1/`、`feature-study-v2/`：历史探索记录；其中含 `harm.*` 的三组使用了有缺陷的音高特征，不能用作有效性结论，已由 v4 替代。其余组复算一致。
- `feature-study-v3/`：预训练结果、逐片分数、带原音 ID/SHA 的特征向量、分组增广结果。
- `feature-study-v4/`：修正音高及静音间隔后，全部十组手工特征的当前结果和图。
- 用户原始标注始终是 `human-annotations-v1.json`；机器预测不会覆盖标签。

## 验证与故障

- 自检通过：静音、短输入、有限数值、固定增益不变性、纯音与白噪声的相关结构、特征组完整性、测试时不修改标准化参数。
- 独立复核发现旧离线脚本直接取最大自相关峰会混淆整数倍周期，且过滤非活跃帧后错误跨静音计算频率变化。改用 librosa YIN，并增加 100/200/300 Hz 纯音频率误差 <3 Hz、稳定性 <0.005 octave、静音间隔不连续的回归。自检及完整重跑均成功；不影响独立 YAMNet 路线。
- YAMNet 重跑结果一致；向量文件携带原音 ID 与 SHA，后续评估先验证顺序与来源。
- 独立复核重放确认增广的原声 9/11、低音量 6/11，未发现同源泄漏；说明“每源权重 1”仅指增广权重之和，类别平衡权重另算。
- 图已检查，原声频谱按各片自身峰值显示，不能跨图比较绝对响度。
- 此轮不修改 Android 生产代码或交互，不需构建/安装 APK；没有上传录音、发布模型或推送仓库。
- 下载失败：首次按搜索结果中的文件名尝试固定提交路径，两个地址返回 404；模型未下载成功，又导致两个文件检查找不到路径。改用 GitHub API 枚举实际文件名，再开启 PowerShell `ErrorActionPreference=Stop`、下载成功后才校验。另一示例仓库路径查询也返回 404，未将空响应当作配置。
- 背景研究的 404/503 和无匹配搜索，以及独立审查一次嵌套引号导致的 SyntaxError，均已改用真实源码路径、缩小查询或无嵌套表达式解决。未找到项目 `agent-incidents` 工具，不追加 AGENTS.md。

原始论文与官方实现的核查来源见 [背景研究](audio-feature-research.md)。本报告的数值来自本机实验，不借用外部论文准确率。
