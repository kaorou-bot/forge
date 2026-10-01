# PC 对战界面扩展接口（版本 1～5）

本接口只用于 Swing PC 客户端。移动端保持原有界面。游戏规则、AI、卡牌选择、区域可见性和目标连线继续由现有控制器处理。

制作皮肤请按阅读对象选择：[玩家入门说明](DesktopMatchSkin-Guide.zh-CN.md) / [AI 制作规范](DesktopMatchSkin-AI-Spec.zh-CN.md)。本文保留接口演进与整合记录。

## cn1002 当前版本

新增能力请阅读 [v4 图标与外观扩展](DesktopMatchSkin-v4.zh-CN.md) 和 [v5 窗口适配与制作体验](DesktopMatchSkin-v5.zh-CN.md)。当前能力清单为 `2026-10-02.1`；[独立皮肤制作资料包 2.0.1](https://update.mtg-forge-kaorou.vip/forge/tools/2.0.15-cn1002/Forge-Skin-Authoring-Kit-2.0.1.zip) 可离线使用，无需源码或主程序。包内 `ready-to-import/dusk-observatory.zip` 为暮辉星台功能示例；整个资料包不能直接作为皮肤导入。

cn1002 已修复自适应战场半屏留白、横置/叠牌尺寸预算、右侧详情空间不足和隐藏浮窗后的滚动箭头残留。下方的整合记录及 v1～v3 配置示例是历史兼容资料，不代表当前推荐制作方式。制作新皮肤优先从 v4/v5 示例复制。

## 2026-09-30 汉化主线整合记录

- 来源：`codex/desktop-match-ui` 的 `8b5ebe09ce0`，共同祖先为 `481eb6842f2`。
- 接入基线：`codex/network-modes-self-hosted` 的 `9f00b1a8910`，同时保留工作区内尚未提交的动态堆叠提示汉化。
- 按功能迁移桌面界面、布局资源、皮肤资源、相关测试及文档，不用旧分支整目录覆盖最新版。未迁移来源分支的共享联机逻辑、网络测试和 iOS 清理提交；已有平台排除规则保持不变。
- `CMatchUI.updateZones` 保留当前版的 EDT 线程切换，切换后才刷新新场景；`VMatchUI` 同时保留断线退出的 `bypassConcedeOnClose` 和新布局管理器。
- 中英文资源各补入 13 个 `lblDesktopMatchUi*` 键，不覆盖现有动态提示翻译或独立来源的卡牌翻译文件。
- 内置 JSON 通过已有 Maven `filters` 资源流程进入桌面 classpath；暮辉秘境作为独立数据皮肤包导入，不要求 Android 包含这些桌面资源。
- 默认继续使用经典布局。示例暮辉秘境为双人皮肤；多人及多受控手牌由容量检查回退，不强行隐藏玩家。
- 此次为本地整合，不变更版本号，不提交、推送或部署服务器。运行中的旧进程不会自动加载新编译的类，验收时应重新启动。

整合验证（2026-09-30，Windows / JDK 21）：

- 专项回归 72 项，失败、错误、跳过均为 0，包括界面四组测试、动态描述翻译、可选费用及网络连接设置。
- `mvn -B -pl forge-gui-desktop -am test` 成功；桌面模块 808 项（失败 0、错误 0、跳过 6），其余依赖模块共 31 项无失败或跳过。跳过项为需要显式启用的批量/压力测试及日志分析等用例。
- 完整回归覆盖本机 3/4/8 人中继对局，不访问线上大厅。Windows 测试进程设置 `-Djdk.net.unixdomain.tmpdir=C:\nonexistent-forge-selector-test`，用于本机回环选择器兼容；不写入发行包配置。
- 中文字库审计 3,927 个码点、0 缺字；平台范围守卫通过；中英文 `lblStack*` 和 `lblDesktopMatchUi*` 合计各 164 键，无重复。
- 桌面、移动共享层和 Android 模块编译通过：`mvn -B -pl forge-gui-desktop,forge-gui-android -am compile -DskipTests`，日志 `dist/diagnostics/desktop-ui-integration-platforms.log`。
- 桌面 `package -DskipTests` 已成功生成 EXE/JAR（非完整发行安装包）；中文工作路径会导致 Launch4j 图标编译失败，需使用既有打包脚本采用的短盘符映射方式。本次日志为 `dist/diagnostics/desktop-ui-integration-package-shortpath.log`，临时映射已移除。
- 可导入的皮肤数据包：`dist/desktop-ui-preview/dusk-sanctum-20260930.zip`，根目录直接包含 `match-ui.json`，无额外目录层；它不是主程序安装包。
- 测试日志：`dist/diagnostics/desktop-ui-integration-tests.log`、`desktop-ui-integration-full.log`。本次自动测试不能替代新皮肤的实机交互验收，尚未重新验收复杂指向、附件及高 DPI 场景。


## 使用入口

进入对战后，打开 **布局 → 对战界面**：

- **经典布局**：默认选项，沿用已有 XML 布局和多人排列设置。
- **暮辉秘境**：内置 v4 双人皮肤示例；旧竞技场和桌面场景预览入口已移除。
- **暮辉星台（v5 功能示例）**：响应式布局、多人方案、个人设置、可记忆的浮窗和几何制作工具。
- **已安装的对战皮肤**：选择已导入并保存的作品；通过导入/删除菜单管理，更新主程序不会清空皮肤库。
- **使用皮肤布局**：从当前皮肤目录读取 UTF-8 `match-ui.json`。没有此文件时使用经典布局。
- **重新加载界面配置**：修改 JSON 后重新读取，无需重启。切换图片皮肤时，若选择了“使用皮肤布局”，也会重新加载。

选择记录在用户 preferences 目录的 `desktop-match-ui.properties`。经典布局继续使用 `match.xml`；自定义区域布局的拖拽结果保存在独立的 `match-ui-<摘要>.xml`。摘要区分界面提供者、皮肤目录、布局定义和实际玩家/手牌组合。修改区域定义会产生新的保存位置；仅修改玩家面板内部位置会即时应用。第 2 版场景使用 JSON 固定定位，不保存拖拽结果。

“重新加载布局”保留已经保存的拖拽结果。“重置对战布局”删除当前模式的保存位置，然后重新使用模板。“保存当前布局 / 打开”仍用于 XML 面板位置，**不包含** JSON 中的玩家面板内部定义。分享完整皮肤需要同时分享 `match-ui.json` 和图像等资源。

## 制作一套布局

完整示范在 `forge-gui-desktop/filters/match-ui/arena.json`，构建后作为 `/match-ui/arena.json` 随 PC JAR 分发。将它复制到当前皮肤文件夹，命名为 `match-ui.json`，再修改即可。

顶层结构：

```json
{
  "version": 1,
  "id": "my-layout",
  "regions": [
    { "bounds": [0, 0, 1, 0.8], "documents": ["fields", "hands", "REPORT_STACK", "CARD_PICTURE", "CARD_DETAIL", "REPORT_LOG", "REPORT_COMBAT", "REPORT_DEPENDENCIES", "BUTTON_DOCK", "DEV_MODE"], "split": "GRID" },
    { "bounds": [0, 0.8, 1, 0.2], "documents": ["REPORT_MESSAGE"] }
  ]
}
```

上例主要说明格式；实际设计可从竞技场示范开始。`version` 必须为整数 `1`。`id` 使用小写字母开头的小写字母、数字、连字符，最多 64 个字符。未知属性、未知组件名、越界、重叠、区域空洞、重复分配和遗漏实际组件会被拒绝，并提示回退到经典界面。不会加载 JSON 指定的 Java 类或脚本。

### 区域

`bounds` 是 `[x, y, width, height]`，以可用对战区域为基准，范围 0～1。所有顶层矩形必须无重叠并铺满可用区域。

`documents` 使用以下稳定标识：

| 标识 | 内容 |
| --- | --- |
| `FIELD_0`～`FIELD_7` | 按当前 Forge 对战视角排序的玩家战场；0 为首个视角玩家，观战时不保证是人类玩家 |
| `fields` | 当前全部玩家战场 |
| `opponents` | 除 `FIELD_0` 以外的战场；表示其余席位，不区分盟友 |
| `HAND_0`～`HAND_7`、`hands` | 当前已有的手牌面板；不会为不可见手牌创建面板 |
| `REPORT_STACK` | 堆叠 |
| `REPORT_MESSAGE` | 提示、确认和取消；必须独占一个单元格 |
| `BUTTON_DOCK` | 对战工具按钮 |
| `CARD_PICTURE`、`CARD_DETAIL` | 卡牌图片、文字详情 |
| `REPORT_LOG`、`REPORT_COMBAT`、`REPORT_DEPENDENCIES` | 日志、战斗、依赖信息 |
| `DEV_MODE` | 仅在开发模式启用时存在 |
| `remaining` | 前面尚未分配的组件，通常放在最后一个工具区域 |

选择器按配置顺序展开。指定一个本局不存在的手牌/玩家会留下空位；这使观战和不同人数共用配置成为可能。`remaining` 不会重复添加同一区域内已选中的组件。工具区域中仍可通过现有区域操作打开坟墓场、放逐区等面板。

`split` 支持 `TABS`（默认，合并成标签页）、`COLUMNS`（横向均分）、`ROWS`（纵向均分）、`GRID`（网格，最后一行铺满）。新布局下，多人排列由配置负责，旧的多人排列菜单禁用。

### 玩家面板内部

可选的 `field` 对每个玩家战场生效：

```json
"field": {
  "BATTLEFIELD": [0, 0, 0.80, 1],
  "PHASES": [0.80, 0, 0.05, 1],
  "AVATAR": [0.85, 0, 0.15, 0.40],
  "DETAILS": [0.85, 0.40, 0.15, 0.60]
}
```

坐标相对当前玩家面板。四项分别为战场滚动区域、阶段条、头像与生命、区域与法术力详情。提供 `field` 时四项必须齐全、无重叠且不能越界；可以保留空白。省略时沿用原有内部排版。窗口变化时重新计算实际像素位置，现有事件监听器和游戏对象不变。

## Java 扩展入口

代码位于 `forge.screens.match.layout`：

- `MatchUiLayoutProvider`：读取或生成布局定义。
- `MatchUiLayout`：描述区域，按当前已有组件展开布局；规划阶段不操作 Swing。
- `MatchFieldLayout`：接收容器及 `AVATAR / PHASES / BATTLEFIELD / DETAILS` 实际组件，自定义玩家面板的组合方式。经典布局也通过此接口实现。
- `DesktopMatchUi.register(id, labelKey, provider)`：在创建布局菜单前注册可信 Java 实现；名称来自现有本地化资源。

Java 扩展应在 Swing EDT 上组合传入组件，保留其身份、事件和可见信息限制，不修改游戏状态。`BATTLEFIELD` 是含原有 `PlayArea` 的滚动面板。保留原有头像和卡牌组件可以让目标连线继续使用其屏幕位置。

例如，注册一个由代码生成区域、使用自定义 `MatchFieldLayout` 的提供者后，菜单会显示它。不要在 `populate` 中增加重复监听器或开启无生命周期管理的计时器。

## 当前边界与验证

第 1 版提供矩形区域和玩家面板的组合。第 2 版增加场景中的独立组件、手牌几何、战场牌叠排列和卡牌覆盖绘制。仍复用已有选牌、出牌、目标连线与游戏控制器；不在 JSON 中执行脚本或定义游戏规则。

## 第 2 版：桌面对战场景

在对战中选择 **Forge → 布局 → 对战界面 → 桌面对战场景（预览）**。内置示例为 `forge-gui-desktop/filters/match-ui/tabletop.json`，可复制到当前皮肤的 `match-ui.json` 后选择“使用皮肤布局”。第 1 版配置和三参数 Java 构造器继续兼容。

场景示例参照上下战场、中间阶段条和底部手牌的结构，同时保留右侧堆叠、卡牌预览、日志与底部操作提示。原停靠栏由独立操作按钮和“对战操作”菜单替代。它是接口的可运行示例，还不是参考图的成品皮肤。

将 `version` 设为 `2`，以 `scene.widgets` 替代 `field`。组件坐标相对整个对战内容区，数值仍为 `[x,y,width,height]`：

| 标识 | 内容 |
| --- | --- |
| `FIELD_n.AVATAR` | 原有头像、生命、玩家选择事件 |
| `FIELD_n.DETAILS` | 原有法术力、区域数量与区域入口 |
| `FIELD_n.AVATAR_IMAGE`、`NAME`、`LIFE`、`STATUS` | 可分别放置的头像、姓名、生命和玩家指示物；后三项同样带 `FIELD_n.` 前缀 |
| `FIELD_n.MANA` 或 `FIELD_n.MANA_W/U/B/R/G/C` | 整行法术力或六个独立颜色控件，保留支付法术力事件 |
| `FIELD_n.ZONE_LIBRARY/GRAVEYARD/EXILE/HAND/FLASHBACK/COMMAND/SIDEBOARD` | 每个区域的独立入口和数量，可分别选用牌堆或文字按钮外观 |
| `FIELD_n.OTHER_ZONES` | 打开原有其他区域的菜单，保留指挥官区、备牌等入口 |
| `FIELD_n.ZONES` | 牌库、坟场、放逐区缩略图及数量（可选） |
| `FIELD_n.HAND_BACKS` | 仅根据手牌数量绘制牌背，超过 30 张时缩略显示并保留真实数量（可选） |
| `PHASES_ACTIVE` | 当前回合玩家的横向阶段条，复用原有阶段停止与让过操作 |
| `STACK_STATUS` | 独立堆叠标题、实时数量及空状态；实际堆叠卡牌由 `REPORT_STACK` 显示 |
| `ACTION_END_TURN`、`ACTION_YIELD_SETTINGS` 等 | 独立操作按钮；后缀对应 `VDock.DockButtonId`，调用现有命令 |
| `ACTIONS_MENU` | 全部原停靠栏操作的菜单；存在此节点时不再挂载 `BUTTON_DOCK` |

`FIELD_n` 文档此时只承载战场，头像和详情不再受战场面板边界限制。每名玩家可使用原 `AVATAR + DETAILS` 组合，也可使用细分组件：`AVATAR_IMAGE + LIFE + STATUS`、全部六色法术力、三个基础区域入口和 `OTHER_ZONES`。中央阶段条必填。容量不满足时回退到竞技场布局。

原组合和其细分组件不能同时配置，例如 `DETAILS` 与 `ZONE_LIBRARY`、`MANA` 会重复承载功能，因此拒绝这种配置。新版示例完全省略 `DETAILS`，旧头像旁的区域按钮不会继续挂载。先前同时使用 `DETAILS + ZONES` 的预览配置需要迁移到细分组件。

场景可以留白，但交互组件和文档区域不能相互覆盖，避免挡住选牌或操作提示。任意绘制层、多层交互命中和脚本动画尚不在 JSON 接口中。

### 容器外观与替换绘制器

在 `scene` 内配置：

```json
"surface": { "border": false, "background": false, "title": false },
"styles": {
  "hands": { "border": false, "background": false, "title": false },
  "REPORT_LOG": { "border": true, "background": true, "title": true }
},
"renderers": {
  "FIELD_0.ZONE_EXILE": "ZONE_BUTTON",
  "FIELD_1.ZONE_EXILE": "ZONE_PILE"
}
```

`surface` 是默认样式，三个开关默认均为 false；`styles` 可按精确文档/组件 ID 覆盖，也支持 `hands`、`fields`、`remaining` 分类。每个样式对象独立定义三个开关，省略的开关为 false。true 表示保留原有对应装饰，不凭空创建不存在的边框或填充。

关闭背景同时停止 `FPanel` 的自绘填充，并清除嵌套滚动层和面板的可见背景/边框。卡牌边缘、选中提示和按钮自身外观保留。手牌仍有用于布局与命中的不可见视口，但不再绘制框线、底色或标题，切回经典布局时恢复原装饰。多文档工具区域保留切换标签，以免隐藏其他文档。

`ZONE_PILE` 与 `ZONE_BUTTON` 是同一逻辑区域的两种绘制器；替换外观后仍使用原区域操作和可见性检查，不同时保留旧按钮。自己的真实手牌始终由 `hands` 文档承载。

可信 Java 扩展可调用 `MatchWidgetRegistry.register("CUSTOM_MY_WIDGET", context -> widget)` 注册组件，或替换既有工厂。`Widget` 提供组件本体、刷新回调和释放回调；`Context` 提供当前对战、所属玩家面板（全局组件为 null）及逻辑类型。JSON 的 `renderers` 仅引用已注册名称，不加载任意类或脚本。替换头像/生命/法术力时应复用其公开的实际控件，保留选择事件和目标连线定位；扩展必须遵循现有可见性权限，并在释放回调清理自行增加的监听器等资源。

场景使用 JSON 固定定位；禁用文档拖动与 XML 布局导入/导出，不读取或写入拖拽布局文件。修改 JSON 后使用“重新加载界面配置”。切回经典或竞技场布局可恢复拖拽。

当前内置场景适用于两名玩家、至多一副可见手牌。超过皮肤声明的玩家数量、缺少对应玩家或同时控制多副手牌时，自动使用竞技场布局，避免隐藏游戏区域。无可见手牌的观战也可以布局。

**对战模式必须保留自己的真实手牌。** `hands` 区域承载原有手牌组件；场景禁止把手牌或战场放进被其他文档遮住的标签组。`FIELD_n.HAND_BACKS` 只是无权查看手牌时的数量示意，不读取牌面；如果该玩家已有可见手牌组件，就不绘制牌背示意。观战仍遵循游戏现有可见性权限，不因皮肤开放额外信息。

卡牌展示配置示例：

```json
"cards": {
  "hand": "fan",
  "fanDegrees": 28,
  "battlefield": "lanes",
  "overlay": "badges"
}
```

- `hand`：`classic` 或 `fan`；`fanDegrees` 为 0～60 度的整体展开角度。绘制和点击使用同一旋转几何；保留选牌、预览和拖动重排。拖动中的浮动卡牌仍使用原有直立动画。
- PC 手牌拖入任一可见战场视口后释放左键，会调用与点击该手牌相同的选择流程。打出地、施放、费用、目标、取消和非法操作均由原控制器处理，不直接移动游戏中的卡牌，也不把落点上的永久物自动选为目标。手牌区内拖动仍用于排序；落到阶段条、日志等非战场区域不触发出牌。经典和自定义布局均支持。
- `battlefield`：`classic` 或 `lanes`。生物与地分行，对手顺序镜像，其他永久物放在右侧；空间不足时滚动。附着物和合并牌叠作为整体移动。
- `overlay`：`classic` 或 `badges`。示例绘制力量/防御力和具名指示物角标，仅接收当前可查看的战场牌的展示数据。

以上各项省略时保持经典行为，`cards` 也能用于不含 `scene` 的第 2 版区域布局。扇形模式下，旧手牌“不重叠”和“每行张数”设置暂不参与计算。

新增 Java 扩展入口：

- `MatchSceneLayout`：声明屏幕中的独立组件位置和玩家容量。
- `MatchCardPresentation`：组合下面三个策略，通过 `MatchUiLayout` 的五参数构造器提供。
- `HandLayoutStrategy`：输入卡牌数量、视口尺寸、最大牌宽，返回每张牌的位置、大小和角度；不接收隐藏牌身份。
- `BattlefieldLayoutStrategy`：输入原有完整牌叠的尺寸和分类，返回各牌叠位置；附着、合并与交互保持原有逻辑。
- `CardOverlayPainter`：在卡牌局部坐标绘制覆盖层，收到力量/防御力、指示物和伤害展示数据，不接收游戏模型。框架为绘制器创建并回收独立 `Graphics2D`。

自动测试 `MatchSceneLayoutTest` 覆盖配置中的交互重叠拒绝、细分组件的功能容量、重复组合拒绝、绘制器兼容性、透明容器及原装饰恢复、FPanel 实际透明像素、观战/多人/控制变化的容量检查、1～100 张手牌的旋转几何与点击范围，以及超宽牌叠与镜像布局。与区域布局测试合计 16 项通过。

拖入战场补充 `HandDropTest`：验证可见视口、裁剪与窗口隔离、单次点击控制器调用、拖动影像先清理、避免同时提交排序，以及控制器拒绝时保留手牌。三组测试合计 18 项通过；这部分为 Swing/控制器回归测试，本轮未重新进行完整实机对局验收。

细分组件这一轮的实机观察确认：旧详情按钮不再显示，头像和法术力各自独立，右上角显示“堆叠 · 0 — 当前为空”，自己的七张真实手牌无框扇形显示，打出地后牌面预览和手牌更新正常。独立区域的右键菜单在此后接入并通过编译；本轮未完整实测非空堆叠交互、区域右键菜单和经典布局往返，不能以之前组合组件版本的测试代替。

桌面场景实机检查使用独立测试配置：2048×1104 窗口下发牌与保留手牌、自己的 7～8 张手牌持续可见、对手牌背与数量、回合阶段更新、选牌预览、进入施放并取消，以及经典与场景布局往返切换。战场牌宽上限在这轮检查后进一步调整并通过编译与自动测试；完整的战斗连线、复杂附着物、网络对战和小屏场景仍需专项验收。

自动测试 `MatchUiLayoutTest` 覆盖 2～8 名玩家、0～全部手牌、开发模式、稀疏手牌编号、布局覆盖与重复检查、操作栏独立性、错误配置以及 Swing 组件重排/缩放/监听器保留。

本次在独立测试用户目录中实际验证了双人对战：竞技场与经典布局往返切换、800×600 窗口与最大化窗口、发牌、保留起手牌、从手牌使用沼泽，以及卡牌预览和日志更新。测试启动使用项目配置的 Java 模块开放参数；本机 JDK 的 Windows Unix-domain socket 回环连接失败，测试进程通过临时 `jdk.net.unixdomain.tmpdir` 设置触发 JDK 的 TCP 回退，未修改产品启动配置。

运行：

```text
mvn -pl forge-gui-desktop -am test -Dtest=MatchUiLayoutTest,MatchSceneLayoutTest,MatchSkinPackageTest,HandDropTest,StackDescriptionLocalizationTest -Dsurefire.failIfNoSpecifiedTests=false
```

上面的命令仅用于界面专项回归。提交前还应在仓库根目录运行 `mvn -B clean test`，保留 Checkstyle 检查和完整测试集；推送后检查 GitHub Actions 中 Java 17、Java 21 两项结果。专项测试通过不能替代完整构建通过。

发布前还应实际验收：多人战场的卡牌密集显示、攻击/阻挡/目标连线、网络对战、区域标签、控制权变化，以及高 DPI 和小窗口下的可读性。通过布局单元测试不等于这些交互已经全部验收。

## v3：独立资源包

v3 在 v2 场景接口上增加条件显示、悬浮文档和外观资源；v1/v2 配置保持兼容。
主程序负责游戏状态、事件与组件生命周期，资源包只提供 JSON、图片、字体，不加载 Java 类或脚本。
首次需要升级到支持 v3 的桌面程序；之后在支持的接口范围内更换布局和美术资源无需重新编译。

新增全局控件 PROMPT_MESSAGE、PROMPT_OK、PROMPT_CANCEL、PROMPT_CONTEXT（可选）。
前三者必须成套声明，替代 REPORT_MESSAGE 文档并保留其原事件处理与键盘操作；
这样提示文字和响应按钮可以各自定位。不要再在固定区域显式分配 REPORT_MESSAGE。
这些控件始终显示，并作为悬浮窗口不能遮挡的区域。独立模式下优先权提醒通过按钮焦点高亮显示。

完整示例为 `skins/dusk-sanctum/`。将其内容打包为 ZIP（根目录直接是 match-ui.json），
在对局的布局菜单中选择「对战界面 → 导入对战皮肤包」即可安装并启用。
安装到用户偏好目录下的 `desktop-match-skins/<id>-<唯一后缀>/`，不覆盖其他皮肤或经典布局。
失败导入会清理临时目录；无效配置在应用时回退经典布局。

`scene.visibility` 将控件 ID 映射到声明式条件：

| 条件 | 适用控件 | 行为 |
| --- | --- | --- |
| ALWAYS | 全部 | 始终显示，默认值 |
| MANA_NONEMPTY | MANA、MANA_W/U/B/R/G/C | 所属玩家六类法术力总数大于零时显示 |
| STACK_NONEMPTY | STACK_STATUS | 游戏堆叠非空时显示 |

生命、阶段和必要操作不能通过条件配置隐藏。PLAYER_ACTIVE 是主程序内部的活动玩家装饰状态，
不作为可隐藏关键控件的配置条件。条件刷新复用现有游戏更新通知，不读取不可见手牌。

`scene.floating` 将文档 ID 映射到
`{"bounds":[x,y,w,h],"visibleWhen":"STACK_NONEMPTY","draggable":true}`。
支持 REPORT_STACK、CARD_PICTURE、CARD_DETAIL、REPORT_LOG、REPORT_COMBAT、
REPORT_DEPENDENCIES、DEV_MODE；默认条件 ALWAYS。浮层仍在游戏窗口内部，复用原组件及交互。
浮动文档不能同时指定在固定区域；浮动堆叠拥有标题，因此不再声明 STACK_STATUS。
初始和拖动位置不能遮挡手牌、REPORT_MESSAGE 区域。窗口缩放时约束在可见范围内。
悬浮面板还会自动避让固定的 CARD_PICTURE/CARD_DETAIL 区域；旧 v3 包若将堆叠放在预览上，
主程序会寻找最近的可用位置，无需重新导入。皮肤应留出足够容纳浮层的非预览空间；
若整个布局无处容纳该尺寸，则保留原位置，制作者应减小 floating.bounds。
拖动事件按帧合并，仅重绘移动前后的区域；背景缩放结果按窗口尺寸缓存。

`scene.appearance` 属性：

| 属性 | 内容 |
| --- | --- |
| background | 包内背景图片相对路径 |
| font | 包内 TTF/OTF 字体相对路径；缺省使用系统无衬线字体 |
| text | 全局文字颜色 |
| styles | 按精确控件 ID 或类别匹配的样式 |

样式类别包含 default、zone、avatar、life、phase、button、text（堆叠等文本区域）、floating、
tab（卡图、卡牌详情、日志等文档标签及溢出按钮）、actions（对战操作窗口背景）。
tab 未声明时回退到 button；actions 未声明时回退到 floating。标签选中态使用 highlight，
切换布局时恢复原皮肤。ACTIONS_MENU 控件打开可拖动、可调整大小的非模态独立窗口；
窗口按钮复用原操作和可用状态，执行后收起，Escape 或关闭按钮也可收起。
其按钮使用 button 样式；窗口随场景释放，不保留跨对局的控制器引用。
每个样式可配置 fill、border、highlight（#RRGGBB 或 #RRGGBBAA）、
radius（0–80）、padding（0–32）、fontSize（8–64）和 image（包内图片路径）。
背景、边框与标题的开关仍由 `scene.surface` 和 `scene.styles` 决定。
真实卡牌面不受主题文字样式覆盖。控件保留原点击逻辑，切换布局时恢复旧字体、颜色和皮肤绑定。

资源限制：ZIP 最多 256 项，单文件不超过 24 MB，解压总计不超过 64 MB，JSON 不超过 1 MB。
只允许 JSON、PNG/JPG/JPEG、TTF/OTF、TXT/MD；禁止越界路径。单图最多 16 MP，全部解码图片最多 32 MP，
相同图片复用缓存。字体许可证须随包保留。

自动化回归增加 MatchSkinPackageTest：真实资源包及中文字体加载、手牌独立可见、
法术力/堆叠状态变化、浮层遮挡拒绝、字体颜色与动态子组件恢复、导入失败回滚和路径越界拒绝。

2026-09-30 补充验收：增加标签选中样式及还原、800/1280/2048 宽度下的预览避让测试。
在独立 Windows 测试对局中检查了标签切换、操作窗口打开及查看套牌命令、拖动手牌出地、
非空堆叠出现和拖向预览边界、移动后背景恢复、堆叠清空隐藏、法术力池出现、经典布局往返。
拖动优化包含重叠组件重绘、固定按下坐标、16 ms 事件合并、背景缓存及避免重复布局；
当前未进行不同显卡、高 DPI 和高负载下的帧时间基准测试。

## cn0930 发布与独立制作资料

`2.0.15-cn0930` 将本接口整合到民间汉化桌面客户端，默认仍为经典布局。
安卓没有接入这一 Swing 桌面皮肤接口；这次安卓更新包含共享引擎的动态描述汉化。

无需获取 Forge 主程序或源码即可开始制作：
[独立皮肤制作资料包 1.0.0](https://update.mtg-forge-kaorou.vip/forge/tools/2.0.15-cn0930/Forge-Skin-Authoring-Kit-1.0.0.zip)。
解压后打开 `开始阅读.html`。资料包含人类入门教程、AI 接口规范、任务模板、完整双人示例、
中文字体及许可、最小骨架、布局示意和静态验收清单。

附带的 Python 3.10+ 标准库工具支持校验目录/ZIP、生成离线 HTML 布局示意及打包，
不联网、不需要 JDK、不改变 Forge 配置。布局示意不是实际游戏渲染；字体缺字、
真实操作、高 DPI 和对局交互仍由兼容客户端验收。
资料包本身不能直接导入，应导入其中 `ready-to-import/` 的皮肤 ZIP 或工具生成的 ZIP。

维护者可运行 `python deploy/build-skin-kit.py --out dist/skin-authoring-kit/新的目录名` 重建资料包。
输出目录及 ZIP 必须不存在；脚本只收集明确指定的文档、工具、示例和许可证，
不会将主程序、账户设置或工作区其他文件打入包内，并运行独立工具测试、生成完整性清单。
