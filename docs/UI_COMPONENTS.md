> **未发布的集成工作区：不是已验收版本。** 本次把 `58a1faec` 的增量与 Time-IU 非官方定制基线 `17a73fdb` 整合；下面的原基线说明不是本次合并的测试结果。所有既有定制功能、语言、非官方归属及构建/许可说明继续保留；新增能力仅为待验证目标，不代表构建、设备端或视觉验收通过。远端采用 PolyForm Noncommercial 1.0.0，与当前 GPL-3.0 基线不同；尚未确认合并及再分发条件，本次不发布、不替换 LICENSE，也不作许可兼容结论。

# Eta 设置与管理组件

Eta 支持全局界面样式切换，提供 **COUI** 与 **Miuix** 两种选择。新安装及升级后尚未明确保存样式选择的用户默认使用 **COUI**；用户在“设置 → 外观与主题 → 界面样式”明确选择后，后续升级保留已选样式。两套界面共享同一套业务状态、Store、Repository 与路由栈，切换样式时通过 `AgentAppTheme` 的 `movableContentOf` 移动同一组合树，不丢失导航历史、页面草稿或正在运行的任务。

## 根主题与缩放不变式

- `AgentAppTheme` 同时提供 `CouiTheme`（COUI 模式）与 `MiuixTheme`，并通过 `LocalUiStyle` 暴露当前 `AppearanceUiStyle`（`COUI` / `MIUIX`）。
- COUI 模式在开启模糊且设备支持 `RuntimeShader` 时，在根部挂载 `ProvideCouiMenuBackdrop(isolateContent = false)`，供全局 COUI `Menu`、`Dialog` 与 `InputDialog` 采样窗口背景。
- 界面缩放以 `configuration.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT` 作为 100% 尺寸基准，并在 `applyInterfaceScale = true` 时同步写入宿主 `context.resources.displayMetrics.density`，保证主页面、独立 Activity（`AgentBrowserActivity`）以及 `Dialog` / `Popup` 独立窗口拥有一致的缩放比例。

## 组件选择与双界面组织

页面层将 `rememberSaveable`、草稿状态、Store 与副作用声明在 `when (LocalUiStyle.current)` 分支之前，再分别组织 COUI 与 Miuix 的界面组合：

| 场景 | COUI 组合（`ui/components/coui/` 及 `com.itos.coui.*`） | Miuix 组合（`ui/components/` 及 `top.yukonga.miuix.kmp.*`） |
| --- | --- | --- |
| 二级页（Column + 回弹滚动） | `CouiScaffoldPage`（`Scaffold` + `LargeTopAppBar` / `TopAppBar` + `couiVerticalScroll`） | `MiuixScaffoldPage` |
| 二级页（长动态列表） | `CouiLazyScaffoldPage` | `MiuixScaffoldPage` |
| 自定义容器二级页（分页、整页编辑器） | `CouiScaffold` | `MiuixScaffold` |
| 顶栏返回与操作按钮 | `CouiBackButton`、`CouiTopAppBarIconButton`、`CouiTopAppBarTextButton`、`TopAppBarButtonGroup` | `MiuixBackButton`、`IconButton`、`TextButton` |
| 分组卡片与标题 | `com.itos.coui.layout.Group(title = ...)` | `SmallTitle` + `Card` |
| 懒加载连续分组条目 | `EtaPreferenceGroupItem` → `CouiGroupItem` | `EtaPreferenceGroupItem` |
| 跳转行 / 信息行 | `CouiPreference`（右侧箭头使用 `CouiIcons.Next`，`12.dp × 24.dp`） | `ArrowPreference` / `BasicComponent` |
| 开关行 | `CouiSwitchPreference`（整行 `Role.Switch` + `CouiHapticFeedback.Switch`） | `SwitchPreference` |
| 下拉 / 弹窗选择行 | `CouiDropdownPreference` / `CouiPopupPreference`（`rememberCouiMenuAnchor` + `Modifier.couiMenuAnchor` + `CouiIcons.Popup` `14.dp × 24.dp` + `Menu`） | `OverlayDropdownPreference` / `WindowSpinnerPreference` / `OverlaySpinnerPreference` |
| 复选 / 单选行 | `CouiCheckboxPreference` / `CouiSelectPreference` | `CheckboxPreference` / `Checkbox` |
| 滑块行 | `CouiSliderPreference` | `SliderPreference` |
| 分组内分割线 | `Divider()`（无前导图标）/ `CouiIconDivider()`（有前导图标） | Miuix 列表间距 |
| 搜索栏 | `CouiSearchBarBox`（COUI `SearchBar` + 局部 `CouiBackdrop`） | `SearchBar` + `InputField` |
| 分页 / 筛选标签 | `CouiChipTabs`（COUI `ChipTabRow`） | `TabRow` / `EtaChoiceChip` |
| 行内输入框 | `CouiTextField` | `TextField` |
| 页面胶囊按钮 | `CouiButton`（`CouiButtonRole`） | `TextButton` |
| 确认 / 提示 / 底部弹框 | `CouiConfirmDialog`、`CouiNoticeDialog`、`com.itos.coui.dialog.Dialog`（`CouiDialogPlacement.Bottom`） | `WindowDialog`、`OverlayDialog`、`OverlayBottomSheet` |
| 单行输入弹窗 | `CouiInputDialog`（`com.itos.coui.dialog.InputDialog`） | `WindowDialog` + `TextField` |
| 上下文 / 溢出菜单 | `com.itos.coui.menu.Menu` + `rememberCouiMenuAnchor` | `WindowListPopup` / `OverlayListPopup` / `OverlayIconDropdownMenu` |

## 分组、分割线与状态边界

- COUI 的 `Group` 内相邻条目之间显式插入 `Divider()` 或 `CouiIconDivider()`；条件行与其前置分割线放在同一可见分支中，避免首尾出现多余分割线。
- 长列表使用稳定 key 的独立 Lazy 条目与 `EtaPreferenceGroupItem`（COUI 自动切换为 `CouiGroupItem`），不将大量动态模型或文件塞进单个 `Group` 或 `Card`。
- 开关、复选、单选、输入框草稿与弹窗开关均由页面或 Store 在 `when (LocalUiStyle.current)` 之上持有，保证运行期切换 `COUI` 与 `Miuix` 时状态与草稿完整保留。

## 远端增量参考（58a1faec，未验收）

以下按完整 Markdown 段落、表格及代码块补入尚未出现在本地正文中的远端描述。它们是待核对的设计与集成目标，不是当前集成已工作的证明；与上方本地约定冲突时不得据此删减本地功能，也不自动采用远端默认值或界面重设计。相关代码、设备权限及双界面行为需后续验证。

### Eta 设置与管理组件

Eta 在现有 Miuix 上提供面向设置、管理列表和功能入口的统一组件。视觉采用 ColorOS 17 风格的分组、图标配色、间距与控件比例；主题、连续圆角、控件动画、系统触感和弹层生命周期继续由 Miuix 提供。组件不依赖特定 ROM，依赖版本以项目构建配置为准。

### 组件选择

| 场景 | 组件 |
| --- | --- |
| 标准列表页 | `MiuixScaffoldPage`，已接入 `EtaPreferenceTheme` |
| 自定义分页或页面 | `MiuixScaffold`，已接入相同主题与宽屏骨架 |
| 卡片容器 | `EtaCard` |
| 设置项分组、分组标题 | `EtaPreferenceGroup`、`EtaPreferenceGroupTitle` |
| 独立懒加载条目组成的卡片 | `EtaPreferenceGroupItem`，传入实际首尾位置 |
| 信息、状态或自定义正文行 | `EtaPreference` |
| 跳转行、开关行 | `EtaArrowPreference`、`EtaSwitchPreference` |
| 自定义行内开关 | `EtaSwitch`，复用同一控件尺寸与 Miuix 行为 |
| 单选、复选行 | `EtaRadioButtonPreference`、`EtaCheckboxPreference` |
| Scaffold 内选择菜单 | `EtaOverlayDropdownPreference` |
| 窗口层选择菜单 | `EtaWindowSpinnerPreference` |
| 需要完整 DropdownItem 的选择菜单 | `EtaDropdownPreference` |
| 图标、正文对齐分割线 | `EtaPreferenceIcon`、`EtaPreferenceDivider` |
| 图标、标题、摘要型功能入口 | `EtaFeatureCard` |
| 按钮、确认按钮行 | `EtaTextButton`、现有 `MiuixDialogActions` |
| 窗口层或 Scaffold 内对话框 | `EtaWindowDialog`、`EtaOverlayDialog` |

`EtaPreferenceDefaults`、`EtaCardDefaults` 和 `EtaControlDefaults` 是尺寸的事实源；`EtaPreferenceColors` 管理分类图标颜色。分类色由调用方按功能选择，品牌图标保留原色，错误及其他运行状态继续使用现有状态语义。

### 分组与边距

`EtaPreferenceGroup` 默认自带页面横向边距和底部组间距。显式传入 `modifier` 时，由调用方管理外部布局；不要再重复叠加一套默认边距。卡片内部设置行自行消费行内边距，表单块按自己的内容需求消费内边距。

```kotlin
EtaPreferenceGroupTitle("连接")
EtaPreferenceGroup {
    EtaArrowPreference(
        title = "服务提供商",
        startAction = {
            EtaPreferenceIcon(icon = providerIcon, tint = EtaPreferenceColors.Blue)
        },
        onClick = onOpenProvider,
    )
    EtaPreferenceDivider()
    EtaSwitchPreference(
        title = "启用连接",
        checked = enabled,
        onCheckedChange = onEnabledChange,
        startAction = {
            EtaPreferenceIcon(icon = connectionIcon, tint = EtaPreferenceColors.Blue)
        },
    )
}
```

无图标行不要提供空的图标插槽；对应分割线使用 `hasLeading = false`。条件行与其前置分割线置于同一可见分支，只在已有前一项时绘制。长列表用稳定 key 的独立 Lazy 条目和 `EtaPreferenceGroupItem`，不要为了卡片外观将模型或文件全集放进一个 Lazy 条目。

### 内容插槽与交互

`EtaPreference` 的尾随 lambda 是自定义正文；无需自定义正文时使用 `title` 和 `summary`。内部 `EtaPreferenceRow` 将自定义正文明确命名为 `titleContent`，尾随 lambda 保留给右侧 `endActions`，防止控件误占正文。

设置行默认不固定文本高度：摘要、长标题和大字体自然撑高。需要把摘要折叠成固定行数时，显式传 `summaryMaxLines`（`EtaPreference`、`EtaPreferenceRow`、`EtaSwitchPreference` 均支持，默认 `Int.MAX_VALUE` 表示不限），组件会自动配 `TextOverflow.Ellipsis`；不要在页面里为了限行另写一份 `Text`。列表入口类页面（如自动任务，摘要 1 行）应直接用该参数，正文放到二级页面。左右内容在一次测量中分配空间，右侧内容有宽度上限，正文保留可换行区域。这个上限是**行宽的 45%**（`EtaPreferenceRow` 内 `actionWidth` 计算），且动作区的 `Row` 不裁剪子项：一行最多放两个紧凑控件，第三个动作要么用图标按钮、要么移到 `bottomAction`，否则会溢出卡片右缘（Linux 工具环境页 Kimi 行实测踩过）。只表达「移除 / 卸载」这类附属动作时用图标按钮：`IconButton`（`minWidth` / `minHeight` 32dp + 18dp 图标，中性 `onSurfaceVariantActions` 着色），不要用开关代替，也不要用主色。底部滑块或附加操作使用 `bottomAction`；无点击回调的展示行不创建虚假的整行点击动作。

开关、复选、单选的状态和持久化由页面或 Store 持有。组件只绘制状态并转发事件；图标禁用态、行语义和子控件语义共同维护，避免读屏重复播报。不要在组件中拼 Entity、写偏好设置或启动业务任务。

### 按钮与弹层

`EtaTextButton` 复用 Miuix 按钮的点击反馈和状态颜色；主要、普通与破坏性操作通过现有 `ButtonDefaults` 配色区分。`MiuixDialogActions` 保留取消在左、确认在右及独立可用状态。

窗口层对话框与 Scaffold 内弹层分别保留各自的宿主机制。不要把 `EtaOverlayDialog` 放在缺少 Scaffold 的上下文中，也不要为了样式把窗口弹层替换成 Overlay。共享弹窗使用主题的卡片表面色，与页面背景及次要按钮底色区分，避免取消按钮融入弹窗。共享封装统一外观参数；确认逻辑、取消回调和长内容滚动仍由调用方负责。

默认浅色设置页使用中性背景、白色卡片和蓝色强调色；Monet 和深色配置沿用 App 的主题。设置页主题不替换聊天正文、终端内容和浏览器画布的专用样式。

### 验证

`EtaPreferenceRenderingTest` 在真实 Compose 布局树中检查开关、单选与选择菜单的标题仍存在，以及有无图标时右侧插槽的位置。控件跨页复用时应继续验证禁用态、条件项、长摘要、大字体、窄屏、输入法及弹层关闭行为。
