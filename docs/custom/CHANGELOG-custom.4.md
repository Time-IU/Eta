# v3.4.4-custom.4 — 本机离线模型管理

基于官方 Eta v3.4.4 的非官方定制测试版，由 Time-IU 维护并使用 AI 辅助。沿用 custom.2 的消息交互和系统 TTS 功能；本次同时同步此前未推送的 custom.3 Vosk 接入与 custom.4 模型管理，不改写既有提交或标签。

## 推荐与可选模型

- SenseVoice Small **Q8_0 为唯一推荐**；其他七款明确标为可选。
- 无有效保存选择时缺省 Q8_0，已有合法已保存选择保持不变；不自动下载、安装或切换用户模型。
- 推荐仅表示本版产品选择，不是同机准确率、启动速度或内存基准排名。

## 删除权重，保留引擎

八款模型均增加“删除已安装权重”，损坏或校验失败的安装也可清理。确认框显示精确模型名及删除范围，COUI/Miuix 两种风格保留独立操作区。

仅清理对应应用私有安装目录的权重与关联安装元数据；保留识别引擎、模型目录、下载/导入能力、其他模型及用户原始 ZIP/GGUF。删除当前选择后仍保留该选择，需重新安装或手动选择其他已安装模型才能录音，不偷偷切模型或回退云端。

- 服务拒绝加载、录音、识别或关闭中的删除；空闲 native 缓存先释放，再在共享文件锁内操作。
- 使用 descriptor-relative `SecureDirectoryStream` 与 `NOFOLLOW_LINKS`；不跟随模型目录或内部软链到外部文件，不支持安全接口的文件系统拒绝删除。
- 请求包含过期时间，锁内再次检查；只有真实 ACK 才报告成功，超时或连接中断显示结果未确认。确认后最长约 90 秒等待，期间不可取消；最后强制检查安装状态。

## 同步 Vosk 双中文模型接入

单一“本机离线”入口保留原六款模型，并增加：

| 模型 | 官方 ZIP 字节 | 解压载荷字节 |
| --- | ---: | ---: |
| vosk-model-small-cn-0.22 | 43,898,754 | 68,292,271 |
| vosk-model-cn-0.22 | 1,358,736,686 | 2,143,476,709 |

可下载或直接导入清单指定的官方原 ZIP，无需手动解压。整包及逐文件 SHA-256、安全有界解压、独立识别进程、流式预览、最终结果单次提交、采集侧停顿检测和约 120 秒空闲释放保留。Vosk 引擎为 Android 0.3.75，JNA 5.18.1；来源、许可证和校验边界见 [模型说明](vosk-models.md) 与 [第三方说明](../../THIRD_PARTY.md)。不接受任意 Vosk ZIP，不在 APK 或仓库内分发模型权重。

## 产物与验证

- APK：`Eta-v3.4.4-custom.4-model-manager.apk`，**52,657,922 字节**。
- APK 内版本：`3.4.4-debug+custom.4-model-manager`；versionCode 沿用 `2026111302`。
- SHA-256：`6b120e389c2bb17b35118383e7ceb87951ca2c1d9eb21ae6e8af31a5c8e49fa1`。
- 完整 Gradle 构建成功；选定 **237 项回归，0 失败、0 错误、0 跳过**，含删除 21 项、推荐 5 项及两个真实官方 ZIP 导入。
- 明确排除两套既有环境不兼容测试：`SystemVoicePolicyTest` 的 SDK28 fixture 低于 minSdk33；`CustomMessageSaveControllerTest` 依赖的 Robolectric API36 native runtime 不支持当前 Linux aarch64。不能称为全项目全测。
- APK 签名及签名前后非签名载荷一致性核对通过，设备 ART 严格验证退出码 0。
- 独立 Android root 进程调用最终 APK 的生产删除实现，验证 scratch 损坏目录/软链清理、外部 sentinel 和其他 scratch 模型保留。该探针不代表 App UID/SELinux、真实 Binder/native 缓存链路或界面视觉验收。

验证摘要：[validation-custom.4.json](validation-custom.4.json)。发布前另外确认提交内的生产源码、资源、测试与上述已验证构建指纹一致；仅补充公开发布文档和可重放验证入口，没有改动 APK。尚未覆盖安装日用版，未删除用户真实模型；真实语音、大模型实际推理、完整删除交互及两种风格视觉验收未完成。

## 安装与构建

安装前备份并核对签名。包名仍为 `io.github.mangi.eta`，定制证书不同于官方，不要为绕过签名冲突卸载日用版。相同历史定制证书 SHA-256：`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`。

构建使用 `-Peta.buildMetadata=custom.4-model-manager`，其余环境与验证命令见 [BUILD.md](../../BUILD.md)。本版源码、许可证和必要构建材料随新标签同步；APK 和校验和放在同标签预发布 Release，不把本地模型或签名私钥上传。
