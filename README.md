# Eta · Release 性能／精简测试版

基于非官方COUI统一版，保留现有功能与外观，将APK从59.7MB缩至28.1MB（约52.9%），并减少流式Markdown的重复完整解析。这里的Release是构建类型，发布仍为测试版。

[下载本版](https://github.com/Time-IU/Eta/releases/tag/v3.4.4-por-perf-compact) · [重点更新／验证边界](docs/publication-perf-compact/release-notes.md) · [构建](BUILD.md) · [第三方来源](THIRD_PARTY.md)

- 保留COUI多级页面、弹层、液态玻璃输入栏、MIUIX与已有配置。
- 采用R8代码优化／资源裁剪，不删语音引擎或功能；同步IO锁验证修复只限制三个类的优化。
- JNI／命令入口及独立链接探针、签名、ART严格校验通过，不等同Hook激活或所有页面已实机验收。
- 没有未经实测的CPU／FPS改善保证；升级前备份，签名不同于官方，不自动安装。

基础能力来自Mangi-11/Eta，整合来源包括Oo0520/Eta-por；保留GPL、PolyForm与第三方适用材料，本次公开上传不等于混合来源许可审核完成或上游背书。原始完整构建输入归档、校验和和R8映射见Release附件。
