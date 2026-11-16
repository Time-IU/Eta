# 定制源码路径映射

基线：官方 `v3.4.4` / `83a25e71eed1e5d8dadbd330362e3999b46595d9`。维护主体 Time-IU，AI 辅助整合。以下路径均相对仓库根目录，描述维护入口，不代表整份文件均由本 fork 原创。

## 消息交互与会话

以下 Kotlin 路径使用公共前缀 `app/src/main/kotlin/io/github/mangi/eta/`。

| 定制行为 | 前缀下的主要路径 |
| --- | --- |
| 统一十项菜单、独立控件与消息接入 | `ui/components/custom/CustomMessageMenu.kt`、`CustomMessageUi.kt`、`ui/components/ChatMessageItem.kt`、`AgentChatBody.kt` |
| 原位原生选择与四项 toolbar；普通拖动不拦截 | `ui/components/custom/CustomMessageSelection.kt`、`CustomMessageInteractionState.kt` |
| 返回键 owner 与单一交互状态 | `ui/components/custom/CustomMessageBackPolicy.kt`、`CustomMessageMenu.kt` |
| 简单 Markdown 引用、保留草稿而不发送 | `ui/components/custom/CustomQuote.kt`、`ui/components/AgentChatInputBar.kt` |
| 记忆/技能审核、确认、结果及防重复操作 | `ui/components/custom/CustomMessageSaveController.kt`、`CustomMessageSaveDialogs.kt`、`CustomMessageActions.kt`、`CustomMessageSkillFormat.kt`、`CustomMessagePersistence.kt` |
| 原生分支边界、引用回退、先保存原草稿 | `ui/components/custom/CustomContinue.kt`、`CustomContinueDialogs.kt`、`ui/app/AgentAppState.kt`、`AgentAppViewModel.kt` |
| 严格离线消息合成、原生播放器、暂停/定位 | `ui/components/custom/CustomSpeechController.kt`、`CustomSpeechControls.kt`、`CustomSpeechLocations.kt`、`CustomSpeechText.kt` |

表格中同一单元格省略目录的文件名沿用该单元格最近的目录；例如 `CustomMessageUi.kt` 位于 `ui/components/custom/`，`AgentChatBody.kt` 位于 `ui/components/`。

手动记忆追加调用上游 `data/repository/AgentMemoryRepository.kt`（同文件内的 `AgentMemoryStore`）的锁和版本机制，受 1 MiB UTF-8 **总文件**上限约束；模型工具的 3500 字符参数限制不变。技能保存复用上游 `agent/skill/` 安装器，不替换已有不同内容；读回验证与重复内容处理在 `CustomMessageActions.kt`。

## 语音、设置与入口

同样使用上述 Kotlin 公共前缀。

| 行为 | 前缀下的主要路径 |
| --- | --- |
| 单一 LOCAL 入口、八模型信息与推荐 | `ui/voice/LocalSpeechSettings.kt`、`LocalSpeechModelInfo.kt`、`agent/voice/local/LocalSpeechManifest.kt`、`LocalSpeechRegistry.kt` |
| 私有模型安装、导入、下载与 SHA-256 验证 | `agent/voice/local/LocalSpeechModelStore.kt`、`LocalSpeechDownload.kt`、`LocalSpeechImport.kt`、`LocalSpeechValidation.kt` |
| 本地引擎生命周期与 JNI 接入 | `agent/voice/local/LocalSpeechService.kt`、`LocalSpeechEngine.kt`、`LocalSpeechRecognizer.kt`、`GgufSpeechNative.kt`；兼容路径 `agent/voice/qwen/`、`agent/voice/offline/` |
| Vosk 流式解码、官方 ZIP 与 IPC 事件泵 | `agent/voice/local/VoskSpeechRecognizer.kt`、`LocalSpeechVoskArchive.kt`、`LocalSpeechClientSession.kt`、`LocalSpeechUtterance.kt` |
| 显式删除、服务确认与安全文件操作 | `agent/voice/local/LocalSpeechDeletion.kt`、`LocalSpeechDeletionClient.kt`、`LocalSpeechModelLock.kt`、`LocalSpeechSafeDelete.kt` 及上述 Store/Service/设置页 |
| 两分钟空闲热缓存策略 | `agent/voice/qwen/QwenWarmCache.kt`、`WarmCachePolicy.kt` 及 `agent/voice/local/LocalSpeechService.kt` |
| 系统 TTS ISO3/Oplus 别名与严格离线音色 | `agent/voice/SystemSpeechPlayback.kt`、`SpeechPlaybackController.kt`、`ui/voice/SystemTtsSettings.kt` |
| 采集侧能量 VAD 与停顿终点 | `agent/voice/SpeechEndpointDetector.kt`、`EndpointSpeechAudioCapture.kt`、`SpeechInputController.kt`、`FileSpeechRecognition.kt`、`StreamingSpeechRecognition.kt` |
| 设置与旧配置迁移 | `data/model/SpeechSettings.kt`、`data/datastore/SettingsDataStore.kt`、`SpeechSettingsMigration.kt`、`ui/voice/SpeechSettingsStore.kt`、`SpeechRecognitionScreen.kt`、`SpeechSynthesisScreen.kt` |
| 电源键/OEM 入口路由 | `core/AssistantEntryRouting.kt`、`hook/system/PowerHooks.kt`、`AssistantManager.kt`、`agent/voice/EtaVoiceInteractionService.kt`、`ui/SettingsScreen.kt` |

每个单元格内的省略目录仍沿用最近目录。全局系统直接 TTS 播放不等于消息菜单的本地音频播放器；在线服务保留但不统一保证 seek。电源键适配也不等于所有 OEM/ROM 均通过。

## 非 Kotlin 文件与许可证

- `app/src/main/assets/local_speech/catalog.json`：八模型格式、精确磁盘字节数、逐文件 SHA-256 和固定 revision 下载来源；无权重。它是模型展示信息的依据，不是全设备准确率测评。
- `app/src/main/assets/local_speech/licenses/`：Qwen/Vosk 模型 Apache-2.0、SenseVoice Model License 及 NOTICE；Vosk/JNA/libffi 许可另保留于 `assets/licenses/vosk-jna/`。SenseVoice 是 FunASR Model Open Source License Agreement v1.1，不等于 runtime MIT 或 Sherpa Apache。
- `app/src/main/cpp/local-speech/`：引入的 [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) runtime 源码及 Eta JNI 接入 `eta_transcribe_jni.cpp`；相关许可同时保留在 `app/src/main/assets/licenses/transcribe/`。引入 runtime 不是原创实现模型或上游第三方运行库。
- `app/src/main/assets/qwen_asr/`、`app/src/main/assets/offline_asr/`：兼容引擎元数据与许可；Sherpa ONNX/NCNN 是 Apache-2.0、ONNX Runtime 是 MIT、NCNN 是 BSD-3-Clause，模型许可仍独立。
- `app/src/main/jniLibs/arm64-v8a/`：对应运行库；native 构建与来源说明见 [BUILD.md](../../BUILD.md) 和 [THIRD_PARTY.md](../../THIRD_PARTY.md)，本页不另立构建流程。
- `app/src/main/AndroidManifest.xml`、`app/proguard-rules.pro`、`app/src/main/res/values/`：语音引擎查询、保留规则与设置文案等配套接入；applicationId 仍以 `app/build.gradle.kts` 为准。

根目录 [LICENSE](../../LICENSE) 与上游版权保留，主项目依上游 README 表述为 GPL-3.0；不额外推定 only/or-later。发布前仍需按组件检查源码、许可、NOTICE 与构建材料，本页不是法律合规认证。不得把私钥、模型权重、真实 API 凭据或私人日志当作发布附件。

## 第三方原始来源

这些链接用于归属和核查，不表示已经完成全部分发合规审查；模型转换来源及固定下载 revision 另见 [catalog.json](../../app/src/main/assets/local_speech/catalog.json)。

- [Eta 上游](https://github.com/Mangi-11/Eta) · [transcribe.cpp / MIT runtime](https://github.com/handy-computer/transcribe.cpp)
- [Sherpa ONNX](https://github.com/k2-fsa/sherpa-onnx) · [Sherpa NCNN](https://github.com/k2-fsa/sherpa-ncnn) · [NCNN](https://github.com/Tencent/ncnn) · [ONNX Runtime](https://github.com/microsoft/onnxruntime)
- [Qwen3-ASR 模型原始来源](https://huggingface.co/Qwen/Qwen3-ASR-0.6B) · [SenseVoice 模型原始来源](https://huggingface.co/FunAudioLLM/SenseVoiceSmall)

## 回归入口与验证边界

测试根为 `app/src/test/kotlin/io/github/mangi/eta/`：

- `ui/components/custom/`：菜单交互状态、返回 owner、保存确认、引用和朗读文本/定位。
- `agent/voice/`：生命周期、播放、系统音色/语言策略、停顿检测；`local/`、`qwen/` 子目录覆盖本地模型相关逻辑。
- `data/repository/AgentMemoryManualAppendTest.kt`、`core/AssistantEntryRoutingTest.kt`：手动记忆与入口策略。

当前 custom.4 完整构建与 237 项选定回归、签名和 ART 验证记录见 [custom.4 变更](CHANGELOG-custom.4.md)；明确排除两套既有环境不兼容套件，不能称为全项目全测。未安装覆盖日用版；独立 root scratch 删除探针不替代 App UID、Binder/cache、真实语音与视觉验收。早期 153 项预览记录仍保留在[历史变更](CHANGELOG-3.4.4.md#最终预览构建记录)。
