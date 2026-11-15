# Eta 3.4.4 个人定制 fork：消息交互与本地语音整理

我是 Time-IU。这是基于 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 官方 v3.4.4 整理的非官方个人 fork，代码整合和文档使用了 AI 辅助。不是“官方加强版”或“破解版”，也不代表原作者背书。

发布准备目标：[Time-IU/Eta](https://github.com/Time-IU/Eta)。本帖不表示代码已推送、Release 已发布或 PR 已提交；源码、变更及构建说明以 fork 实际页面为准。

主要是把个人常用流程理顺：

- 消息长按统一十项紧凑菜单；正文原位选字，浮动项只留复制、朗读、追问、完成；朗读工具条独立，普通列表拖动不受选字干扰。
- 引用用简单 Markdown，保留草稿，不自动发送。保存记忆/技能先审核确认、再保存并反馈结果，不覆盖不同内容的已有技能。手动记忆追加受 1 MiB UTF-8 文件总量限制，不改变模型工具的 3500 字符限制。
- 新对话继续：适用的助手边界沿用原生分支；其他情况审核后仅引用所选内容，不假装继承整个上下文。原草稿先保存，两种方式都不自动发消息。
- 系统 TTS 修正语言代码与部分 Oplus 音色元数据适配；消息朗读只选可确认离线的音色，合成本地音频后支持暂停和定位。官方在线 TTS 保留，不是所有 TTS 入口都支持 seek。
- LOCAL 入口集中六款本地识别：Qwen3-ASR 0.6B Q4_K_M / Q5_K_M 混合量化 GGUF，SenseVoice Small F16 GGUF、Q8_0 GGUF、Q4_K_M 混合量化 GGUF、INT8 ONNX + tokens。默认推荐 INT8；需要手动下载或导入，SHA-256 校验后私有安装，APK 不带权重。精确大小见[模型表](https://github.com/Time-IU/Eta/blob/main/docs/custom/CHANGELOG-3.4.4.md#local-本地-asr)。
- native ASR 当前走 CPU，约两分钟空闲热缓存是在延迟与 RAM 间取舍，不是模型准确率排名；模型落盘体积也不是运行内存。没有做 CER/WER 排名。
- 停顿检测统一在录音采集侧处理，使用自适应能量 VAD，不是神经网络降噪判断。“2 秒”是静音停录阈值，不保证两秒出字；按住说话、手动和 0 值保持手动语义，不自动转云端。

官方 3.4.4 的 DeepSeek ASR/TTS、凭据复用、侧边栏和依赖更新照常保留。长期记忆、Skills、Agent Runtime、原有工具与语音基础都来自上游，不算我的原创。

个人日用反馈良好，设备兼容未普验。部分电源键/OEM 功能仍需 Root、LSPosed 模块激活、作用域和重启，具体看 ROM。离线的是相应 ASR/朗读路径，LLM/API 仍按配置联网，不能说整个助手完全离线。

安装请注意：包名仍是 `io.github.mangi.eta`，定制签名不是官方签名，不能保证覆盖官方包。debug 预览体积较大；先备份，别为签名冲突贸然卸载导致数据丢失，也不承诺跨签名迁移。

最终预览为 `Eta-v3.4.4-pause-autosend-fix-preview.apk`，48,794,514 B；SHA-256：`70a6c6a74273a0869b38e8b4d38cfa9168416a9439285facccd14df873c43bb8`。已有交付记录是 153 测试、0 失败、0 跳过及 ART 验证通过；不是全设备兼容证明，最终包的现场麦克风和安装结果未确认。

定制问题先到 [fork Issues](https://github.com/Time-IU/Eta/issues)，附机型、ROM、入口和复现，注意脱敏；请不要拿本定制问题打扰上游。未来可能整理适合的改动给上游，但目前不承诺 PR。

感谢上游与相关开源项目。主项目沿用 GPL-3.0、保留原版权与 LICENSE；运行库和模型另按各自许可，不能把 runtime 的 MIT 当成 SenseVoice 模型许可。详见 [定制说明](https://github.com/Time-IU/Eta/blob/main/docs/custom/README.md) 和[第三方说明](https://github.com/Time-IU/Eta/blob/main/THIRD_PARTY.md)，这不是法律合规认证。
