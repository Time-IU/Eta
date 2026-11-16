# 3.4.4 定制变更

> 本页保留早期 custom.1 预览记录；当前 custom.4 的八模型、Q8_0 推荐、删除功能与 237 项选定回归结果见 [custom.4 变更](CHANGELOG-custom.4.md)。以下六模型、INT8 默认与 153 项测试不代表当前版配置。

维护：Time-IU；AI 辅助整合与验证。基线为官方 `v3.4.4` / `83a25e71eed1e5d8dadbd330362e3999b46595d9`。以下描述当前源码与最终预览构建，不把较早预览当作最终版，也不表示仓库已推送。

## 保留的上游更新

保留 3.4.4 的 DeepSeek ASR/TTS、API Key/凭据复用、侧边栏及依赖升级。Agent Runtime、长期记忆、Skills 和原有工具体系来自上游。本 fork 主要增加交互、手动保存与本地语音接入，不重新声称上述体系的原创权。

## 消息交互

- 紧凑菜单固定十个位置：复制、选取文字、朗读、分享、删除、引用追问、保存记忆、保存技能、在新对话中继续、重新生成/编辑。不可用项给出原因；朗读控件独立于菜单。
- 在消息正文原位使用原生选择容器；进入选取后仅保留“复制 / 朗读 / 追问 / 完成”四项，不叠加重复系统项目。普通浏览不截获列表拖动，返回处理检查实际 owner。
- 引用使用简单 Markdown `>`，保留当前输入草稿，不自动发送。
- 保存记忆/技能先审核、再明确确认，成功后给出读回结果；防重复确认，技能同内容重复保存走幂等检查，不覆盖已有不同内容。失败不会显示为成功。
- 手动记忆追加受 **1 MiB UTF-8 文件总量**约束，不截断；这不是单条无限容量，也不改变模型 `memory_write` 工具原有 3500 字符参数限制。技能仍受原安装器的元数据、包与文件大小限制。
- “新对话继续”有两条明确路径：符合条件的助手消息边界沿用上游原生分支及其边界内历史；选中文字或其他不适用原生分支的情况使用审核后的引用回退。引用回退只带入所选引用，不继承整段会话，原草稿先持久化留在原会话；两条路径都不自动发送。

## 系统朗读与在线 TTS 的区分

- 系统 TTS 规范化 ISO3 语言/国家代码，针对已确认的 Oplus 元数据别名做窄范围适配；严格离线策略只选择可确认不需网络的音色，无法确认时明确报错，不暗中改用云端。全局系统 TTS 的“使用系统默认”是单独显式选项，不是失败后的自动回退。
- 消息菜单里的定制朗读通过系统离线音色合成为本地音频，再使用原生播放器，提供暂停、定位与分句控制；缺少离线音色时不可用，不自动安装音色。
- 上游在线 TTS 能力保留。直接系统语音播放与在线服务各有能力边界，**不能宣称所有入口或所有服务都能 seek**；消息定制播放器的能力不等于全局 TTS 能力。

## LOCAL 本地 ASR

单一 LOCAL 入口提供六个模型 profile。默认推荐 `sensevoice_small_int8`，不代表启动就安装。以下大小直接来自 [catalog.json](../../app/src/main/assets/local_speech/catalog.json)，表示模型载荷落盘大小，**不是 RAM/RSS**，也不是 APK 大小；MiB = 1,048,576 B。

| 模型 | 容器 | 载荷字节（B） | MiB |
| --- | --- | ---: | ---: |
| [Qwen3-ASR 0.6B Q4_K_M（混合量化）](https://huggingface.co/handy-computer/Qwen3-ASR-0.6B-gguf/resolve/7e7c69a577fa2725dfedbe34b5e999336e30a19b/Qwen3-ASR-0.6B-Q4_K_M.gguf) | GGUF | 589,560,480 | 562.25 |
| [Qwen3-ASR 0.6B Q5_K_M（混合量化）](https://huggingface.co/handy-computer/Qwen3-ASR-0.6B-gguf/resolve/7e7c69a577fa2725dfedbe34b5e999336e30a19b/Qwen3-ASR-0.6B-Q5_K_M.gguf) | GGUF | 645,356,192 | 615.46 |
| [SenseVoice Small FP16（F16 GGUF）](https://huggingface.co/handy-computer/SenseVoiceSmall-gguf/resolve/ff8f085a8fa2e5ccae0e849ef19ebc6dc18e608b/SenseVoiceSmall-F16.gguf) | GGUF | 470,412,128 | 448.62 |
| [SenseVoice Small Q8_0](https://huggingface.co/handy-computer/SenseVoiceSmall-gguf/resolve/ff8f085a8fa2e5ccae0e849ef19ebc6dc18e608b/SenseVoiceSmall-Q8_0.gguf) | GGUF | 252,684,608 | 240.98 |
| [SenseVoice Small Q4_K_M（混合量化）](https://huggingface.co/handy-computer/SenseVoiceSmall-gguf/resolve/ff8f085a8fa2e5ccae0e849ef19ebc6dc18e608b/SenseVoiceSmall-Q4_K_M.gguf) | GGUF | 145,738,304 | 138.99 |
| [SenseVoice Small INT8（ONNX）](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/model.int8.onnx) | ONNX | 239,549,735 | 228.45 |

`Q4_K_M` / `Q5_K_M` 是混合量化，不能省略 `_M` 宣称 exact Q4_K/Q5_K。SenseVoice FP16 profile 实际为 **F16 GGUF**，不是 FP16 ONNX；GGUF 自带词表。INT8 ONNX 包含 `model.int8.onnx`（239,233,841 B）和 `tokens.txt`（315,894 B），合计 239,549,735 B；不是原始 safetensors 直载。表内模型名链接到清单固定 revision 的原始下载地址；完整文件列表及散列以清单为准。

- 所有下载/导入均需用户主动操作；载荷安装在应用私有目录，按清单逐文件校验 SHA-256，不随 APK 分发模型权重，也不静默下载或上云兜底。
- native 推理按当前接入使用 CPU，不宣称 GPU/NPU 加速。空闲约两分钟的热缓存减少重复加载，也可能提高常驻 RAM；磁盘大小、运行峰值内存与速度不能混作准确率排名。
- 六模型清单记录了两段公开 WAV 的设备侧验证，不是 CER/WER 基准，也不能证明 Qwen 或某量化“最准”。性能与 RAM 峰值受设备、模型和音频长度影响。

## 停顿检测与电源入口

- 采集侧共用自适应能量 VAD，根据音频样本时钟检测停顿；不是神经网络语音/噪声分类器。在本地、流式及文件路径统一处理，关闭冲突的本地 native endpoint 消费；文件识别前释放录音资源，已采集 PCM 继续交给识别流程。
- “2 秒”是选定策略下的静音停录阈值，不是“两秒内必出文字”的截止时间；模型解码/网络仍需时间。手动、按住说话与值为 0 的手动语义保留，不强制自动发送，不自动上云。
- 部分 OEM 电源键路由及默认助理状态处理做了适配；Root/LSPosed 路径需模块激活、正确作用域和重启，不能保证跨 ROM 通用。离线 ASR 不会让在线 LLM 变成本地推理。

## 最终预览构建记录

- 文件：`Eta-v3.4.4-pause-autosend-fix-preview.apk`；大小 **48,794,514 B**。
- SHA-256：`70a6c6a74273a0869b38e8b4d38cfa9168416a9439285facccd14df873c43bb8`。
- 源码基础 `versionName = 3.4.4`，debug 构建标记为 `3.4.4-debug`，`versionCode = 2026111302`；预览文件名不等于新的官方版本号。
- 最终交付记录：构建通过；153 项回归测试、0 失败、0 跳过；ART 验证退出码 0。113 项测试属于较早预览，不是最终统计。
- 上述是已有交付记录，本次文档整理没有重新运行构建或测试。最终记录未确认安装、未做现场麦克风验证；不据此声称已实测安装或全机型通过。另有个人日用反馈良好，设备兼容未普验。

保持相同 applicationId 不等于签名兼容。安装与许可边界见[索引](README.md)；源码入口见[路径映射](MODIFICATIONS.md)。
