# 第三方代码、运行库与可选模型

主项目保留 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 的历史、署名及根目录 [GPL-3.0 LICENSE](LICENSE)。本文件是来源索引，不替代任何组件的许可证原文，也不是对完整法律合规的认证。已有上游组件继续遵循上游原说明。

## 本 fork 新增或适配的组件

| 组件 | 本仓库路径/用途 | 来源、版本与许可 |
| --- | --- | --- |
| transcribe.cpp + ggml | `app/src/main/cpp/local-speech/`；五种 GGUF 识别 | [handy-computer/transcribe.cpp](https://github.com/handy-computer/transcribe.cpp/tree/adfc50210a3f026a655a3e19937d90ef2bf0728c)，MIT；内部 ggml、miniz 等许可证见其保留的 `THIRD-PARTY-LICENSES.md` |
| 自定义 JNI glue | `app/src/main/cpp/local-speech/eta_transcribe_jni.cpp` | 本 fork 的集成修改，源码与 CMake 一起保留；不冒称 transcribe 上游原文件 |
| libc++ runtime | `libc++_shared.so` | Termux 官方 ARM64 `libc++` 包，LLVM/Apache-2.0 WITH LLVM-exception，完整原文在 `assets/licenses/transcribe/LLVM-libcxx-LICENSE.TXT` |
| Sherpa-ONNX Kotlin/JNI | `com/k2fsa/sherpa/onnx/`、`libsherpa-onnx-jni.so` | [k2-fsa/sherpa-onnx v2.0.1](https://github.com/k2-fsa/sherpa-onnx/tree/af2d8d81dbfa61c4cea4c568da78f3982edca005)，Apache-2.0；JNI 对应官方 Android release |
| ONNX Runtime | `libonnxruntime.so` | Microsoft ONNX Runtime，预编译文件来自上述 Sherpa Android release；MIT，许可及第三方 notices 在 `assets/qwen_asr/` |
| Sherpa-NCNN wrapper/旧兼容库 | `com/k2fsa/sherpa/ncnn/`、两份 NCNN .so | 参考 [Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI/tree/036e4cdc4e0d5c8aa3e8dd192aa4ddf441984e24) 的 Apache-2.0 语音实现；Sherpa-NCNN Apache-2.0，Tencent NCNN BSD-3-Clause；详见 `assets/offline_asr/NOTICE.md` |

原 native 版权与许可证没有被主项目 GPL 文本覆盖或抹去。集成说明、静态库来源及本地修改应分别看待。

## Native 文件与构建来源

[精确库清单](docs/custom/native-artifacts.json) 记录新增库的尺寸、SHA-256、来源及可重建性边界，不含私钥或签名凭据。

- `libeta_transcribe_jni.so` 在本轮开发中用 Termux 官方 Clang 21.1.8 重新构建；实际源码及 Eta 修改已完整放入本仓库。C Release 优化必须启用。标准 NDK 重新构建说明见 [BUILD.md](BUILD.md)，历史工具链来源见 native 清单。
- `libsherpa-onnx-jni.so`、`libonnxruntime.so` 来自官方固定 release archive：
  `https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-android.tar.bz2`
  归档 SHA-256：`39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330`。
- 旧 `libsherpa-ncnn-jni.so`、`libncnn.so` 是从用户提供的 ZorvAI-v1.2.4.apk 提取的预编译运行库，不是本 fork 自己编译。已保留其公开来源和逐文件哈希，但该参考 APK 未提供精确 native commit、构建参数及完整静态依赖版本，所以这里不编造或保证它们的逐字节重建。后续维护者应进一步补全这部分 provenance。
- APK 中其他原有 native 组件（PTY、proot 等）沿用上游文件及 `assets/native-sources/` 材料，不列为本 fork 新作。

## 模型与 runtime 的许可证分开

没有模型权重随本仓库或本定制 APK 打包。下载/导入由用户显式触发，权重完整哈希、固定 revision、文件名与大小均来自 `assets/local_speech/catalog.json`。

- **Qwen3-ASR-0.6B**：原 Qwen 模型 Apache-2.0；本 fork 支持的 Q4_K_M/Q5_K_M 是注明转换来源的第三方 GGUF，不冒称 Qwen 团队原生 GGUF。Qwen 许可与 NOTICE 保留在 `assets/local_speech/licenses/` 及 `assets/licenses/transcribe/`。
- **SenseVoiceSmall**：采用独立的 **FunASR Model Open Source License Agreement v1.1**，不能称为与主程序一样的 GPL 或与 runtime 一样的 MIT。模型许可证原文在 `assets/local_speech/licenses/SenseVoice-Model-License-1.1.txt`。F16/Q8_0/Q4_K_M GGUF 与 INT8 ONNX 的转换、词表和 hash 分别记录；Q4_K_M 不是 exact Q4_K。
- legacy Qwen ONNX 转换包的归档没有独立 LICENSE/NOTICE；对应 NOTICE 如实保留了这一材料缺口，而不是假称完整审查已完成。

请在再分发模型或提供模型镜像前单独核对相应条款。提供下载来源不等于重新许可模型，也不表示开发者代用户同意所有模型条款。

## 署名及关系

本 fork 不代表 Eta、ZorvAI、Qwen、FunAudioLLM、Sherpa、ONNX Runtime 或 transcribe.cpp 作者背书。参考代码只用于相应语音集成，没有迁移其他应用的账号、API Key、聊天记录或私有录音。本文只描述源码来源和集成边界，准确率、跨设备兼容性及复现情况见 [定制变更说明](docs/custom/CHANGELOG-3.4.4.md)。
