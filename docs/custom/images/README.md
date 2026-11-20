# 首页动态图素材

本目录新增的四个 GIF 是为 Time-IU/Eta 首页原创绘制的**交互／流程示意**，不是实机录屏。画面使用虚构文本，每帧均保留说明水印；不使用用户聊天、账号、录音或第三方录屏，不作为性能或设备验收证据。

| 文件 | 说明 | 依据 |
| --- | --- | --- |
| `chat-search.gif` | 完整当前会话正文查找、匹配切换与定位；长模型名选择面板 | custom.5 查找实现与发布说明 |
| `offline-models.gif` | 本机离线模型推荐、按需导入、单个权重删除范围 | custom.4 模型管理，custom.5 保留 |
| `message-actions.gif` | 统一消息菜单、选字引用、保存预览、离线消息播放器 | custom 系列消息交互实现 |
| `vendor-entry.gif` | 小布／超级小爱经鉴权桥接进入 Eta Runtime，以及取消保护 | custom.5 厂商桥接实现；未完成厂商端到端验收 |

对应公开代码：`v3.4.4-custom.5`，应用代码合并提交 `96f9c37c2fcfe0fb727f3e5d772de5a7c261d2b9`；同步的上游快照 `0b70953453b173405a4d9dcfad54be78cf3aec70`。不展示后续未公开 integration-por 的界面或功能。

README 的“先讲差异、再链接上游文档”组织方式参考 [Oo0520/Eta-por 的 README（58a1faec 快照）](https://github.com/Oo0520/Eta-por/blob/58a1faec3cdc1a43bac55f2617729710429bd15f/README.md)，未复制其正文、演示素材或功能归属。

## 重新生成

依赖 Python、Pillow 与本地 Noto Sans CJK 字体；脚本不联网、不操作设备，不将字体打包进仓库：

```sh
python render_demos.py --font /path/to/NotoSansCJK-Regular.ttc
```

`render_demos.py` 仅为文档素材生成器，不参与 APK 构建。每个 GIF 循环播放，900 × 520，16–20 帧。目录原有 `custom5-overview.png` 是历史静态示意图，保留以免破坏既有 Release 文档链接。
