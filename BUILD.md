# 构建此定制源码

> 非官方 fork，基于 Mangi-11/Eta v3.4.4（`83a25e71eed1e5d8dadbd330362e3999b46595d9`）。上游 `LICENSE`、版本声明及依赖版本未被替换。修改范围见 [定制说明](docs/custom/README.md)。

## 1. 环境

- JDK 25。
- 使用仓库 Gradle Wrapper 9.7.1，无需另外安装 Gradle。
- Android SDK Platform 37.0、Build-Tools 36.0.0；首次构建前由操作者阅读并接受对应 Android SDK 许可。
- 能运行所需 Android 构建工具的主机。标准 Linux 构建优先使用 x86_64；在 ARM64 Android/Linux 中不能直接运行官方 Linux x86_64 AAPT2。ARM64 需要经过验证的原生 AAPT2，并通过 Gradle override 显式指定，不能为此降低 compileSdk/Kotlin/AGP。
- 当前预览构建使用 Kotlin 2.4.21、AGP 9.5.1、COUI alpha11；以 `gradle/libs.versions.toml` 为准。

配置自己的 SDK 目录（不要提交 local.properties）：

```sh
export JAVA_HOME=/path/to/jdk-25
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
sh ./gradlew --version
sh ./gradlew --no-daemon :app:assembleDebug
```

预览源码保留上游 applicationId `io.github.mangi.eta`，本地 debug 构建版本名为 `3.4.4-debug`。Debug 体积较大不是包含了模型：工程只内置模型清单、许可及推理运行库，权重需用户另行安装。

## 2. 验证

以下是对应预览构建执行的选定回归套件，不应将其写成“项目全部测试”或“所有机型均已验收”：

```sh
sh ./gradlew --no-daemon :app:testDebugUnitTest \
  --tests 'io.github.mangi.eta.ui.components.custom.CustomQuoteTest' \
  --tests 'io.github.mangi.eta.ui.components.custom.CustomSpeechTextTest' \
  --tests 'io.github.mangi.eta.ui.components.custom.CustomSpeechLocationsTest' \
  --tests 'io.github.mangi.eta.ui.components.custom.CustomMessageBackPolicyTest' \
  --tests 'io.github.mangi.eta.ui.components.custom.CustomMessageInteractionStateTest' \
  --tests 'io.github.mangi.eta.ui.voice.LocalSpeechModelPresentationTest' \
  --tests 'io.github.mangi.eta.agent.voice.local.*' \
  --tests 'io.github.mangi.eta.agent.voice.qwen.*' \
  --tests 'io.github.mangi.eta.agent.voice.MimoSpeechRecognitionTest' \
  --tests 'io.github.mangi.eta.agent.voice.MimoSpeechSynthesisTest' \
  --tests 'io.github.mangi.eta.agent.voice.DeepSeekSpeechRecognitionTest' \
  --tests 'io.github.mangi.eta.agent.voice.DeepSeekSpeechSynthesisTest' \
  --tests 'io.github.mangi.eta.agent.voice.SpeechEndpointDetectorTest' \
  --tests 'io.github.mangi.eta.agent.voice.LocalSpeechLifecycleTest' \
  --tests 'io.github.mangi.eta.agent.voice.FileSpeechLifecycleTest' \
  --tests 'io.github.mangi.eta.agent.voice.SpeechLifecycleTest' \
  --tests 'io.github.mangi.eta.data.model.SpeechSettingsTest'
```

历史预览结果：153 项、0 失败、0 跳过。Robolectric 首次运行需要解析其 Android 测试运行库；它们不是用户手机中的 Android 系统。

如需全项目检查，另行运行 `:app:lintDebug :app:testDebugUnitTest`。这些更广泛检查未被本页的 153 项结果代替。实机还需验证收音、停顿、取消、音频焦点、后台回收、ROM 电源键路由、选区和窄屏布局；ART 校验不能代替它们。

### custom.4 的构建与选定回归

当前版本说明及结果见 [custom.4 变更](docs/custom/CHANGELOG-custom.4.md) 和 [公开验证摘要](docs/custom/validation-custom.4.json)。版本元数据使用 `-Peta.buildMetadata=custom.4-model-manager`；未修改上游 versionCode，也不自动迁移已保存模型选择。

以下是对应 237 项选定回归的可重放入口。`ETA_VOSK_OFFICIAL_CACHE` 指向仓库外目录，放入 [Vosk 说明](docs/custom/vosk-models.md) 指定的两个官方原 ZIP，并核对摘要。未指定缓存时真包测试会明确跳过，不会隐式联网；这种运行不能声称复现 237 项零跳过。

```sh
export ETA_VOSK_OFFICIAL_CACHE=/absolute/path/to/vosk-official-cache
# 仅为复现本次 Linux aarch64 环境启用这两套明确排除；其他主机不要盲目设置。
export ETA_MODEL_MANAGER_ARM64_EXCLUSIONS=1
# 本次先构建未签名包，后用既有定制证书签名；该项不提供或导出任何私钥。
export ETA_MODEL_MANAGER_UNSIGNED=1
sh ./gradlew --no-daemon --console=plain --max-workers=2 \
  -I scripts/custom/verify-model-manager.init.gradle \
  -Peta.buildMetadata=custom.4-model-manager \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleDebug :app:testDebugUnitTest \
  --tests 'io.github.mangi.eta.agent.voice.local.*' \
  --tests 'io.github.mangi.eta.agent.voice.qwen.*' \
  --tests 'io.github.mangi.eta.ui.voice.*' \
  --tests 'io.github.mangi.eta.agent.voice.*SpeechLifecycleTest' \
  --tests 'io.github.mangi.eta.agent.voice.SpeechEndpointDetectorTest' \
  --tests 'io.github.mangi.eta.agent.voice.SystemTtsPolicyTest' \
  --tests 'io.github.mangi.eta.agent.voice.SystemVoicePolicyTest' \
  --tests 'io.github.mangi.eta.ui.components.custom.*' \
  --tests 'io.github.mangi.eta.ui.components.AgentChatScrollPolicyTest' \
  --tests 'io.github.mangi.eta.agent.overlay.*'
```

ARM64 仍需按环境指定 `-Pandroid.aapt2FromMavenOverride=/absolute/path/to/aapt2`（实际文件名为 `aapt2`）。本次使用 2300 MiB Gradle heap、640 MiB metaspace、Java 25 与预先验证的原生 AAPT2；不以降低 SDK/AGP 来适配工具。构建环境的隔离挂载属于宿主环境配置，不要求其他开发者照搬设备私有路径。

本次明确排除：`SystemVoicePolicyTest`（SDK28 fixture 低于 minSdk33）、`CustomMessageSaveControllerTest`（Robolectric API36 native runtime 不支持 Linux aarch64）。排除由可选 init 配置显式打印，不修改产品源码，不把两项环境失败算作通过。237 项包含新增删除 21 项、推荐 5 项、两个官方 ZIP 的生产导入。正常主机可不设排除变量以运行更广泛验证，但结果应以实际输出为准。

原始完整构建成功，包装脚本曾因误猜 APK 文件名在复制阶段退出 91；已从实际 Gradle 输出取回同一未签名产物并校验，不把包装失败隐藏为编译失败或重新构建成功。最终 APK 的签名和非签名载荷一致、ART 严格验证、独立 root scratch 生产删除探针均通过；App UID/Binder/native-cache 全链路、真实语音及界面视觉仍待验收。

## 3. 原生运行库

应用构建默认使用 `app/src/main/jniLibs/arm64-v8a/` 中的对应预编译运行库。因此普通 APK 构建不会自动重新编译整个 transcribe.cpp。额外的原生库及源码来源、SHA-256 见 [THIRD_PARTY.md](THIRD_PARTY.md) 与 `docs/custom/native-artifacts.json`。

自己编译的 GGUF JNI 对应源码完整放在：

```text
app/src/main/cpp/local-speech/
  eta_transcribe_jni.cpp
  eta-native.cmake
  eta-jni.map
  include/ src/ ggml/ ...
```

`eta-native.cmake` 禁用 GPU、BLAS、OpenMP、KleidiAI 和动态后端，并显式设置 C Release 为 `-O3 -DNDEBUG`。不要清空 C 优化参数：此前它会导致量化解码极慢。

支持 NDK 的主机可执行：

```sh
# 设置已安装的 NDK 与 CMake/Ninja；此命令不代替操作者接受许可。
export ANDROID_NDK_HOME=/path/to/android-ndk
sh scripts/custom/build-local-speech-native.sh
```

脚本输出单独的 native build 目录，不自动替换仓库已验证的 .so。更换产物前应检查 AArch64 ABI、DT_NEEDED、所有 PT_LOAD 的 16 KiB 对齐和 JNI 符号，并重新做六模型真实推理。重新编译的产物不保证逐字节等于历史 Termux Clang 构建；工具链、STL 和编译参数均影响结果。

Sherpa/ONNX Runtime/NCNN 为有标明来源的第三方预编译产物，不能冒称本轮编译。尤其旧 NCNN 两库的精确编译 commit/参数没有随参考 APK 提供，本仓库没有编造它们；详见第三方来源说明。模型下载器不需要公开或继承原 App 的密钥和用户数据。

## 4. 签名与发布

构建或分享者必须使用自己掌控的签名。仓库不包含 MT 私钥、keystore、API Key、OAuth token、聊天记录或模型权重。历史定制证书的 SHA-256 仅作为公开核对信息：

```text
a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc
```

拿到源码不代表拿到该私钥，也不代表你的 debug/release 产物可覆盖官方原签或历史定制安装。不要因签名冲突贸然卸载日用版本。原上游 release 配置通过 `ETA_RELEASE_*` 环境变量读取操作者自行设置的凭据；不要把这些值写进代码或 Git。

上游 workflow 保留未改；fork 若启用其 main/tag 构建，发布 job 会要求 fork 自己的签名 Secrets。这不表示上游作者的 Secrets 会传给 fork，也不表示本文已经触发或完成任何远程构建。

公开分发修改后的 APK 时应同时提供与之对应的源码版本、必要构建材料及第三方许可说明，按项目和各组件许可证处理。不能只提供上游原仓库链接，却省略实际修改。
