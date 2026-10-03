# 睡眠音频特征背景研究

日期：2026-10-03（本机日期）。范围：仅原始研究与官方实现；不读取或上传原音/标注，不下载模型，不修改生产代码。本文是主任务委派的研究子任务，未再创建任务。实现成本均为相对工程估计，非手机实测。

## 结论与证据边界

- 用户提供的现状：Kotlin 仅有 3 频带与 0.3–3 秒慢包络周期；12 个标注为 6 鼾声、2 背景、床板/说话/接触异常/需上下文叹气各 1。本文不独立验证这些标签。
- **优先补“片段内部的快速纹理 + 谱形及变化”，不要把完整呼吸周期当作短鼾声的必要条件。** 这是工程建议：原始研究分别采用事件内部时频特征、周围节律，以及 0.3 秒窗分类；并非证明本数据上任何单特征有效。[P1][P2]
- P1 的非鼾类别包含 bedding noise、talking 等，但不是专门“床板吱响”分类基准；P2 为二分类，不能引用其总准确率证明本项目细分类有效。[P1][P2]
- **K 的呼吸风险疑虑不是医学真值。** 不训练“危险/安全/呼吸暂停”标签，不根据音高不稳、停顿、模型分数推断疾病。音频事件分类与医学判断是不同任务；P1 的人工标注有同步 PSG 呼吸信号辅助，本项目没有等价依据。[P1]

## 候选特征：收益、局限、成本

下表“本项目用途”为待本机对照的假设，不是文献已经验证的床板/说话分界。建议起点为 16 kHz 单声道、25–40 ms 帧和 10 ms 步长；音高可用更长帧，保留有效帧数与截断标记。参数需预先固定，不在 12 个标签上反复择优。[P1][T1][L1]

| 特征 | 本项目用途 / 好处 | 局限与防误判 | 端侧相对成本 |
| --- | --- | --- | --- |
| Time texture：短时 RMS、峰均比、包络差分/峰密度、ZCR；谱通量、平坦度、质心的时序及分位数 | 描述攻击/衰减、脉冲与持续性，补足 3 个宽频带丢失的变化；对照短鼾、瞬态接触声、床板声及稳定背景 | 截断会制造突变；归一化会放大静音；床板也可能有规则振动。平坦度是噪声样/音调样指标，不是声源标签；不能用一个阈值定类。[P1][L2] | RMS/ZCR 等低；共用 STFT 后的谱统计低至中 |
| Pitch / harmonicity：YIN 或 pYIN 的 F0、可信周期帧占比、归一化自相关峰；谐波能量占比 | 把毫秒尺度振动周期与秒级包络分开；观察谐波是否连续、F0 是否跳变；短片段不必有完整呼吸周期 | 说话与床板均可能周期化；低 SNR、混叠/倍频、非周期鼾会导致 F0 不可靠。YIN 会返回候选，不能将“有数值”当有声；pYIN 提供 voiced flag/probability，但不是鼾声概率。[L1] | 自相关/YIN 中；pYIN 候选与 Viterbi 解码更高，先离线对照 |
| Jitter / F0 稳定性 | 在可靠周期段描述振动不规则，作为辅助维度 | **帧间 F0 变化不等于逐周期 jitter**；本次核查的 YIN/pYIN 接口不直接输出 jitter。周期提取不可信、有效周期太少时记缺失，不记 0；不能赋予风险含义。是否区分类别尚无本项目证据。[L1] | 帧间统计低；真正周期定位与异常周期处理额外中等成本 |
| MFCC / log-mel：保留帧序列，辅以均值、离散度和变化量 | 多个细频带刻画谱包络；MFCC 是紧凑表示，log-mel 保留较直观的时频结构。对照说话谱形变化与鼾声纹理 | MFCC 本身不是 pitch/harmonicity；低阶压缩与全片平均会丢细节。受设备、距离、混响影响；短段 delta 的边界填充可造假变化。P1 使用 MFCC 等特征并不证明其单独有效。[P1][L2] | 共用 FFT 后低至中；只算少量 MFCC 的 DCT 开销较小 |
| Modulation spectrum：分带包络去均值后分析调制能量 | 补充片段内快脉动；与慢呼吸节律分开。纹理原始研究使用分带包络及多级调制统计，说明表示有感知价值。[P3] | P3 是纹理合成/听觉实验，**不是鼾声分类验证**。长度 T 的频率分辨率约为 1/T：0.3 秒约 3.3 Hz，不能可靠识别低频呼吸节律；补零不增加真实信息。全片平均仍丢顺序 | 已有分带包络时中等；完整多级滤波器组高，不建议首轮复刻 |
| YAMNet scores / 1024 维 embedding | 官方类别含 Speech、Snoring、Sigh、Breathing、Creak、Noise；可作独立声学参照，embedding 可供后续轻量分类器使用。[T1][T2] | Creak 不等于床板；预训练声源与卧室设备存在域差异。sigmoid 是多标签分数，不互斥，也不是医学风险或本域校准概率。12 例不足以可信训练高维分类头。[T1][T2][S1] | 高于轻量 DSP；MobileNet 推理需实测延迟、峰值内存、整晚耗电，本次未测 |

## 短片段与端侧实现要点

1. **不要混淆三种时间尺度。** F0/谐波来自波形快速周期；调制谱来自包络变化；呼吸节律来自多事件上下文。P1 用事件周围的能量自相关及前 10 秒信息；短片段缺失上下文时应输出 unavailable/需上下文，而非“不是鼾声”。[P1][L1][P3]
2. **YAMNet 输入核实：** 16 kHz；25 ms STFT 窗、10 ms hop；64 mel 带，125–7500 Hz；`log(mel + 0.001)`。特征窗 0.96 秒、hop 0.48 秒，首窗实际波形至少 0.975 秒（15600 样本），官方包装会补零。[T1]
3. **不要把 librosa 默认 MFCC 前端直接当作 YAMNet 前端。** 官方 YAMNet 对幅度谱作 mel 投影和自然对数；librosa MFCC 默认从功率 mel 谱的 dB 表示计算。窗函数、mel 约定、幅度/功率、对数、padding 均需逐项匹配。[T1][L2]
4. **补零不是恢复上下文。** 工程建议：保留有效样本比例，分开记录原段、真实邻域扩展、截断/补零条件；可对同一事件做平移/裁剪一致性对照，但不能把这些派生片段算新独立样本。静音补齐占多数的分数只能谨慎解释。[T1][S1]
5. **Kotlin/TFLite 路线：** 官方 Task Library `AudioClassifier` 支持 Android 音频输入与分类，但要求模型兼容、含相应 metadata；不可假设任意转换模型可直接接入。要获取 embedding，须检查导出模型的输出张量，不能假设分类 API 自动返回 embedding。[T1][T3]
6. **DSP 成本也要核查实现。** YAMNet 官方 TFLite 兼容 STFT 分支用矩阵乘法实现 DFT，并非直接调用 FFT；不能仅凭“相同特征”推断同样能耗。先用主 agent 的本机特征对照确定候选，再另做真机 profiling。[T1]

## 本轮对照与评估约束

- 建议按固定顺序做消融：现有特征 -> 快速纹理 -> log-mel/MFCC -> 可信周期特征 -> 调制谱；YAMNet 留作后续可选参照。本轮不下载模型。分别查看短鼾漏检、床板/说话误报、接触异常与背景，而不只报总体准确率。此为研究建议，尚未执行。
- **8/4 划分的 12 个标签已经见过，不能称为真正 blind holdout。** 可作开发/回归分组，但特征、阈值与模型选择已可能受到这些标签影响。真正测试需要方案冻结后新采集、未参与选择的独立数据。[S1][S2]
- **singleton class 不能评估该类别泛化。** 床板、说话、接触异常等各仅 1 个：留出后训练侧没有该类实例，留在训练则测试侧无该类；裁剪/增强也不增加独立来源。可报告个案成败，不宣称类别召回率可靠；二分类合并同样不能证明细分类覆盖。[S1]
- 后续按人/夜晚/原始事件的独立性组织分组，确保同一原始事件及重叠窗不跨训练测试；组数不足时明确不可估计。标准化、PCA、特征选择只在训练部分拟合；阈值调优不可触碰最终测试集。[S1][S2]
- K 仅保留用户疑虑的来源说明，禁止转成风险监督目标。叹气样本保留“需 context”，不为了凑类别强行改标。

## 核查来源

下列均为已读取的原始研究正文或官方文档/源码；论文镜像来自 Europe PMC。未引用综述结论，未把摘要索引或不可读 PDF 当正文证据。官方 `master/main` 为浮动版本，未来实现需固定版本。

- [P1] *Automatic detection of whole night snoring events using non-contact microphone* (2013). [DOI](https://doi.org/10.1371/journal.pone.0084139)；[全文 XML](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC3877189/fullTextXML)。研究同时使用时域/谱域/上下文，标注与设备条件不同于本项目。
- [P2] *Automatic snoring detection using a hybrid 1D-2D convolutional neural network* (2023). [DOI](https://doi.org/10.1038/s41598-023-41170-w)；[全文 XML](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC10462688/fullTextXML)。0.3 秒窗不等于已验证任意截断鼾声。
- [P3] *Cascaded Amplitude Modulations in Sound Texture Perception* (2017). [DOI](https://doi.org/10.3389/fnins.2017.00485)；[全文 XML](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC5601004/fullTextXML)。仅支持调制纹理表示动机，不外推分类准确率。
- [T1] TensorFlow 官方 YAMNet：[README](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/README.md)、[features.py](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/features.py)、[params.py](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/params.py)、[yamnet.py](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/yamnet.py)。
- [T2] TensorFlow 官方 [YAMNet class map](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/yamnet_class_map.csv)。
- [T3] Google/TensorFlow 官方 [Task Library AudioClassifier](https://www.tensorflow.org/lite/inference_with_metadata/task_library/audio_classifier)（现站点导航为 LiteRT）。
- [L1] librosa 官方 [pitch.py](https://github.com/librosa/librosa/blob/main/librosa/core/pitch.py)：YIN/pYIN 算法、输出和限制；内含原始论文书目信息，但本次未另行读取那两篇论文正文。
- [L2] librosa 官方 [spectral.py](https://github.com/librosa/librosa/blob/main/librosa/feature/spectral.py)：RMS/ZCR/谱统计、MFCC 与 mel 实现。
- [S1] scikit-learn 官方 [Cross-validation / GroupKFold](https://scikit-learn.org/stable/modules/cross_validation.html)。
- [S2] scikit-learn 官方 [Common pitfalls / Data leakage](https://scikit-learn.org/stable/common_pitfalls.html)。

## 检索与执行记录

- Web 搜索两次返回空结果，未据此声称“没有研究”；改用官方 HTTP 和 Europe PMC 原文。所有请求仅含公共地址/研究关键词，没有发送音频或标签。
- Shell 内 HTTP 请求失败：librosa `doc/latest` 与 `doc/main` 的部分生成页、TensorFlow `yamnet_transfer_learning` 页面、一个 CMU 论文 PDF 地址均返回 404。可确认该次请求地址不可用，不能确定是永久迁移还是站点路由；改读成功获取的官方源码，不反复猜测路径。
- Europe PMC 一次查询返回 503：服务端该次不可用，确切原因未知；后续缩小查询成功。另一次年份过滤未按预期限制结果，不能信赖该结果集的年份，改用具体标题定位原文。
- `rg --files -g '*incident*' -g '*AGENTS*' -g '*research*' -g '*log*'` 退出 1、无输出：这是无匹配，不是程序崩溃。可见项目文件中未发现 `agent-incidents` 记录工具，因此仅在本文记录；以后区分 rg 的退出 1（无匹配）与退出 2（错误），不将其误报为执行故障。
