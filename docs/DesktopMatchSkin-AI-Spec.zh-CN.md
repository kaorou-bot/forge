# Forge 桌面对战皮肤 AI 制作规范

版本提示：本文主体固定为 v3 基线。v4 测试客户端的新增字段和替代限制见 [v4 扩展规范](DesktopMatchSkin-v4.zh-CN.md)；v4 保留本文的安全边界和必需操作，但允许该补充规范明确列出的状态、图片模式、装饰、图标、头像轮廓和卡牌几何设置。未升级客户端仍只能使用 v3。

本文供 AI 编码助手、皮肤工具开发者和需要逐项核对接口的制作者使用。目标是在不改变游戏逻辑和信息权限的前提下，制作可导入、可回退、可验证的桌面对战数据皮肤。

规范核对日期：2026-09-30。适用对象是已整合 `desktop-match-ui` 功能的 Swing 桌面客户端，JSON 格式版本为 1、2、3，新数据皮肤应使用版本 3。客户端产品版本号与 JSON `version` 是两回事；不能仅凭 `cn0929r2` 等产品版本判断是否已包含尚未发布的接口。

如果代码与本文不一致，以目标客户端的实际解析器和测试为准，报告差异后再制作。不要将本文视作未来版本的无条件兼容承诺。入门说明见 [皮肤制作入门](DesktopMatchSkin-Guide.zh-CN.md)，背景设计记录见 [桌面对战接口](DesktopMatchUI.zh-CN.md)。以下源码路径均相对仓库根目录。

## 任务边界

制作数据皮肤通常只应修改一个皮肤目录中的 JSON、图片、字体和说明。不得擅自修改规则引擎、AI、网络协议、翻译数据、资源下载渠道、安装器、自动更新或线上服务器。不要删除其他作者的工作区文件。

数据包不能执行 Java、JavaScript、Lua、HTML 或 CSS，也不能通过 JSON 下载外部资源。当前接口没有任意动画系统、分辨率媒体查询、自动布局约束求解器、任意 z-index 或通用鼠标悬停样式。不要编造 `hover`、`animation`、`anchor`、`zIndex`、`minWidth`、`breakpoints`、`opacity` 等字段。未知字段在多数配置对象中会被拒绝。

当前功能只作用于桌面对局画面，不是 Android 皮肤、首页主题或卡图替换方案。若用户要求超出数据接口的功能，应区分“需要主程序扩展”与“可以仅改皮肤”，先说明范围，不把它伪装成可用 JSON。

## 优先采用的工作流程

1. 阅读目标仓库的相关解析器和本文，不依赖旧对话记忆。
2. 复制 `skins/dusk-sanctum/` 到新的目录，不直接覆盖原件；为新作品分配独立 `id`。
3. 写明适用人数、观战支持、目标窗口尺寸和用户需要的风格。未指定时可以以双人、可回退布局为起点，并说明假设。
4. 先保持布局，只修改图片、颜色和字体，验证资源加载；再逐块调整几何。
5. 在导出 ZIP 前做静态检查、调用真实 Java 解析器、检查实际文档覆盖和容量。
6. 用目标客户端导入，再进行完整交互检查。没有运行环境时明确标记“仅静态验证，未实机验收”。
7. 交付皮肤 ZIP、可编辑源目录、使用说明、素材来源与测试记录。数据皮肤不是主程序安装包。

## 代码依据和调用链

主要代码目录：`forge-gui-desktop/src/main/java/forge/screens/match/layout/`。

| 类或文件 | 需要核对的职责 |
| --- | --- |
| `MatchSkinPackages` | ZIP 导入、大小限制、持久化皮肤目录、枚举、受限删除、安装失败清理 |
| `MatchUiLayout` | 顶层字段、区域几何、选择器展开、文档完整性 |
| `MatchSceneLayout` | 控件合法性、重复组件、可见条件、浮层、容量与重叠 |
| `MatchWidgetRegistry` | 当前真实存在的组件和 renderer 名称 |
| `MatchSkinTheme` | 主题字段、样式选择、资源解码、字体、颜色 |
| `MatchSurfaceStyle` | 原边框、底色、标题开关及样式还原 |
| `MatchFloatingSpec`、`MatchFloatingPanel` | 可悬浮文档、默认值、拖动与避让 |
| `MatchSceneView` | 控件创建和生命周期、屏幕缩放、背景与浮层 |
| `DesktopMatchUi` | 提供者选择、保存路径、容量回退与异常回退 |
| `MatchCardPresentation` | 手牌、战场和牌面覆盖策略配置 |
| `HandLayoutStrategy`、`BattlefieldLayoutStrategy` | 几何规则及牌叠排列 |
| `../views/VDock.java` | `DockButtonId` 操作枚举 |
| `forge-gui-desktop/filters/match-ui/` | 内置 arena 和 tabletop 配置 |
| `skins/dusk-sanctum/` | 版本 3 的完整图片、字体和 JSON 示例 |

主路径为：ZIP 解压到临时目录 → `MatchUiLayout.read` 及资源校验 → 移动为独立安装目录 → 用户选择该包 → `DesktopMatchUi.prepare` → 容量检查 → `arrange` → 安装区域和场景组件。

重要区别：导入时没有当前对局的文档集合。因此“ZIP 导入成功”只证明导入阶段通过，不能证明该配置在所有人数和手牌组合下都通过 `arrange` 或能正确交互。

## 文件和导入约定

ZIP 根目录直接包含 `match-ui.json`。建议结构如下：

```text
match-ui.json
images/table.png
fonts/NotoSansCJKsc-Regular.otf
fonts/OFL.txt
README.md
ASSETS.md
```

使用 UTF-8 JSON，建议无 BOM。生成严格 JSON：数字、布尔值使用正确类型，不用字符串代替，不含注释、重复键、尾随逗号或非有限数值。不要依赖解析库可能容忍的非标准写法。

资源路径相对包根，使用 `/`，保持大小写与真实文件一致。拒绝绝对路径、盘符、反斜杠、越界 `..`、符号链接逃逸和不存在的文件。只引用包内资源，不引用用户电脑上的字体或网络 URL。

| 限制 | 实际约束 |
| --- | --- |
| 格式 | 普通 ZIP；不要加密 |
| 条目数 | 最多 256，文件和目录均计入 |
| 单文件解压大小 | 不超过 24 × 1024 × 1024 字节 |
| 总解压文件大小 | 不超过 64 × 1024 × 1024 字节 |
| 配置大小 | `match-ui.json` 不超过 1 × 1024 × 1024 字节 |
| 扩展名 | json、png、jpg、jpeg、ttf、otf、txt、md |
| 单张已解码图片 | 不超过 16,777,216 像素 |
| 本次主题加载的不同图片总量 | 不超过 33,554,432 像素；同一路径图片复用缓存 |
| 字体 | 能被 Java `Font.createFont` 实际加载，并覆盖目标文字；扩展名正确不等于文件有效 |
| 重名 | ZIP 条目名称忽略大小写后也不能重复 |

许可是交付要求：保留字体原许可和图片来源，不能把未授权素材包装成原创。解析器不自动验证素材授权。

失败导入会清理本次 `.import-*` 临时目录，不覆盖已有安装。成功目录名为 `<id>-<8位随机后缀>`；重复导入生成新副本。不要承诺相同 `id` 会原地升级旧皮肤。

## 顶层 JSON 结构

| 字段 | 必填和类型 | 说明 |
| --- | --- | --- |
| `version` | 必填整数 | 1、2 或 3；新包使用 3 |
| `id` | 必填字符串 | 正则 `[a-z][a-z0-9-]{0,63}`，总长 1～64 |
| `regions` | 必填数组 | 1～64 个固定文档区域 |
| `field` | 可选对象 | 玩家面板内部布局；不能与 `scene` 同时出现 |
| `scene` | 版本 2/3 可用 | 独立组件场景；ZIP 导入器要求存在 scene |
| `cards` | 版本 2/3 可用 | 卡牌展示策略；省略为经典行为 |

版本 1 不允许 `scene/cards`。版本 2 的 scene 不允许 `visibility/floating/appearance`。版本 3 支持这些新字段。旧版区域配置可通过“使用皮肤布局”使用，但不能把无 scene 的版本 1 配置当作新 ZIP 皮肤直接导入。

## 坐标与固定文档区域

`bounds` 始终为 `[x,y,w,h]`。在 `regions` 和 `scene.widgets` 中，相对于整个对战内容区；在旧 `field` 中，相对于当前玩家面板。不要混用像素值和比例。

要求四个数有限，`x,y >= 0`，`w,h > 0`，`x+w,y+h <= 1`。实现允许约 0.000001 的浮点边界误差，制作时不要主动利用误差。边缘相接合法，内部交叠不合法。

每个 region 只接受 `bounds`、`documents`、`split`。`documents` 必须非空；`split` 默认 `TABS`，枚举必须大写：

| split | 含义 |
| --- | --- |
| `TABS` | 一个单元格，多文档通过标签切换 |
| `COLUMNS` | 水平均分 |
| `ROWS` | 垂直均分 |
| `GRID` | 接近正方形的网格，最后一行占满该行宽度 |

没有 scene 时，regions 必须无重叠且铺满内容区；有 scene 时允许空白，但所有固定 region 与所有 widget 两两不能重叠。即使 widget 条件隐藏，几何验证仍按存在计算。

### 文档名称和选择器

| 名称 | 用途 |
| --- | --- |
| `FIELD_0` … `FIELD_7` | 当前视角排序的玩家战场 |
| `HAND_0` … `HAND_7` | 当前真正存在的可见手牌面板 |
| `fields` | 全部已有 FIELD |
| `opponents` | 除 FIELD_0 外全部 FIELD，不等同于规则意义上的敌人 |
| `hands` | 全部已有 HAND，可能为空或编号不连续 |
| `REPORT_STACK` | 堆叠内容 |
| `REPORT_MESSAGE` | 原提示、确认、取消；未被独立控件替代时必须独占 cell |
| `BUTTON_DOCK` | 原对战工具栏 |
| `CARD_PICTURE`、`CARD_DETAIL` | 卡图预览、文字详情 |
| `REPORT_LOG`、`REPORT_COMBAT`、`REPORT_DEPENDENCIES` | 日志、战斗信息、依赖信息 |
| `DEV_MODE` | 仅开发模式实际存在 |
| `remaining` | 尚未分配的已有文档，建议作为末尾工具区的兜底 |

选择器按 region 和 documents 的顺序处理。每个实际文档必须恰好分配一次，除非被 scene 的明确替代机制接管；显式指定不存在的文档不会创建新游戏信息，可能形成空 cell。重复分配或漏分配会失败。

scene 中真实 HAND 和 FIELD 不能与其他文档放进同一个标签 cell。不得为了通过检查移除真实手牌、战场或响应入口，也不要通过手工构造对手手牌绕过可见权限。

## 场景组件

scene 的合法键为 `widgets`、`surface`、`styles`、`renderers`；版本 3 还允许 `visibility`、`floating`、`appearance`。`widgets` 必填，值为组件名到 bounds 的映射。

### 全局组件

| ID | 约束和用途 |
| --- | --- |
| `PHASES_ACTIVE` | 必须存在；默认显示当前回合玩家的阶段；v4 可用 PHASES_SPLIT 同时呈现双方，保留原阶段操作 |
| `PROMPT_MESSAGE` | 提示滚动区 |
| `PROMPT_OK` | 原确认按钮 |
| `PROMPT_CANCEL` | 原取消按钮 |
| `PROMPT_CONTEXT` | 可选的局数等上下文标签 |
| `STACK_STATUS` | 堆叠标题和数量，不替代实际堆叠内容 |
| `ACTIONS_MENU` | 原对战操作集合的非模态独立窗口入口 |
| `ACTION_<枚举名>` | 调用已有操作，不定义新的游戏行为 |

只要出现任何 `PROMPT_*`，就必须同时有 MESSAGE、OK、CANCEL 三项。使用独立提示后不再把 `REPORT_MESSAGE` 放入固定区域。使用 `ACTIONS_MENU` 后不再固定分配 `BUTTON_DOCK`。仅增加几个独立 ACTION 并不等于替代完整工具栏：没有 ACTIONS_MENU 时仍需分配 BUTTON_DOCK。

当前操作枚举：`AUTO_PASS`、`YIELD_SETTINGS`、`MACRO_RECORD`、`MACRO_PLAY`、`END_TURN`、`ALPHA_STRIKE`、`TARGETING`、`AUTO_YIELDS`、`VIEW_DECK_LIST`、`CONCEDE`、`OFFER_DRAW`。例如 `ACTION_END_TURN`。操作是否可用仍由原按钮和控制器决定。

### 玩家组件

以下后缀都必须加 `FIELD_n.`，其中 n 为 0～7。`FIELD_0` 是当前首个视角，不保证在观战时属于人类。

| 后缀 | 功能 |
| --- | --- |
| `AVATAR` | 原组合头像区域，包含生命等控件 |
| `AVATAR_IMAGE`、`LIFE`、`STATUS` | 拆开的头像、生命、玩家指示物 |
| `NAME` | 玩家姓名，独立可选 |
| `DETAILS` | 原组合详情区域 |
| `MANA` | 整行法术力 |
| `MANA_W`、`MANA_U`、`MANA_B`、`MANA_R`、`MANA_G`、`MANA_C` | 分开的六类法术力控件 |
| `ZONES` | 牌库、坟墓场、放逐区组合视图 |
| `ZONE_LIBRARY`、`ZONE_GRAVEYARD`、`ZONE_EXILE` | 基础区域的独立入口 |
| `ZONE_HAND`、`ZONE_FLASHBACK`、`ZONE_COMMAND`、`ZONE_SIDEBOARD` | 其他独立区域入口 |
| `OTHER_ZONES` | 其他区域菜单，保留原游戏可见性检查 |
| `HAND_BACKS` | 无可见手牌面板时显示牌背数量，不读取牌面；最多画 30 张牌背并保留真实数量 |

功能拆分不是复制：同一个 Swing 控件不能同时在两个位置拥有父容器。解析器拒绝以下组合：

- AVATAR 与 AVATAR_IMAGE、LIFE、STATUS 中任意一个共存。
- DETAILS 与 MANA/MANA_*、ZONES/ZONE_*、OTHER_ZONES 中任意一个共存。
- ZONES 与任意 ZONE_* 共存。
- MANA 与任意 MANA_* 共存。

### 容量检查与回退

每个实际玩家必须具备以下两组能力：

1. AVATAR，或 AVATAR_IMAGE + LIFE + STATUS。
2. DETAILS，或完整法术力 + OTHER_ZONES + 基础区域入口。

其中完整法术力是 MANA 或 W/U/B/R/G/C 全六项；基础区域入口是 ZONES 或 LIBRARY/GRAVEYARD/EXILE 全三项。NAME 和 HAND_BACKS 不补足这些要求。

还要求配置声明的 FIELD 玩家在当前对局中存在。当前 scene 路径一旦有超过一副真实可见手牌就返回不支持，即使自行画了多个手牌区域也不能消除这一限制。

容量不满足 → 回退内置 arena 区域布局；解析、资源或实际排版异常 → 经典布局并提示。两种回退不可混为一谈。只改 `opponents` 为 GRID，不能把只含两个玩家 widget 的皮肤变成完整八人场景。

## 外观配置分为两层

### 原面板装饰开关

`scene.surface` 是默认装饰对象；`scene.styles` 按精确组件/文档 ID 或分类覆盖。每个对象只允许 `border`、`background`、`title` 三个布尔值，缺省均为 false。某字段为 true 表示保留原装饰，不保证为原本无装饰的控件新增装饰。

查找顺序为：精确 ID → 非 FIELD_0 的 FIELD 使用 opponents（若存在）→ HAND 使用 hands / FIELD 使用 fields / 其他使用 remaining → surface。

多文档区域仍保留标签切换入口，不能借 title=false 让其他文档无法访问。scene.styles 中的键必须是现有 widget 或合法文档选择器。

### 第三版主题美术

`scene.appearance` 只接受：

| 字段 | 含义与默认值 |
| --- | --- |
| `background` | 可选包内图片，随内容区拉伸，不自动保持原比例 |
| `font` | 可选包内 TTF/OTF；省略使用逻辑 SansSerif，建议显式提供中文字体 |
| `text` | 全局文字颜色，默认白色 |
| `styles` | 主题样式字典，区别于 scene.styles |

每个主题样式只接受下面这些字段：

| 字段 | 类型和范围 | 省略时 |
| --- | --- | --- |
| `fill` | `#RRGGBB` 或 `#RRGGBBAA` | 无底色 |
| `border` | 同上 | 无主题边线 |
| `highlight` | 同上 | 无强调色 |
| `radius` | 整数 0～80 | 8 |
| `padding` | 整数 0～32 | 2 |
| `fontSize` | 整数 8～64 | 15 |
| `image` | 包内图片路径 | 无图片 |

注意 RGBA 的透明度在最后，不是 ARGB。`fontSize` 当前必须为整数，不要根据 Java 字段为 float 就输出 14.5。尺寸类样式参数使用 Swing 的绘制/布局单位，不是 0～1 相对坐标。

样式不是 CSS 继承：命中某个样式后，省略字段使用上表默认值，不从 styles.default 按字段合并。比如 default.fontSize=20，而 life 只写 fill，那么 life 的字号仍是 15。需要保持的属性应明确写入。

样式选择先尝试精确请求 ID，再走类别，最后 default：

| 请求类型或组件 | 类别回退 |
| --- | --- |
| ZONE_* | zone |
| AVATAR* | avatar |
| LIFE | life |
| PHASES_ACTIVE | phase |
| ACTION*、OTHER_ZONES、PROMPT_OK、PROMPT_CANCEL | button |
| actions | floating |
| tab | button |
| 其他 | default |

精确指定玩家生命的写法是 `"FIELD_0.LIFE": {...}`；只写大写 `LIFE` 不等同于所有生命控件。常用样式键为 default、avatar、life、zone、phase、button、floating、tab、actions、text。未知主题样式名可能被接受却从未被请求，不能因此声称已生效。

实际文档内容经 `theme.apply(..., "document")` 时，还会为其子组件选择 text（文本编辑组件）、button（可操作按钮）或 default。真实 `CardPanel` 被跳过；主题不改卡牌本身的画面。样式图片绘制在底色之后、边线之前，并拉伸到控件尺寸；大面积不透明图片可能遮住底色效果。

highlight 用于特定激活、标签选中或按下绘制逻辑，不是通用 hover。玩家头像和生命可随当前回合高亮。不要把“有这个颜色字段”误写为“所有控件都有同一种动画”。

## 条件显示

`scene.visibility` 的键必须是已有 widget。允许的组合如下：

| 条件 | 可用位置 |
| --- | --- |
| `ALWAYS` | 任意已有 widget |
| `MANA_NONEMPTY` | MANA 或 MANA_* |
| `STACK_NONEMPTY` | STACK_STATUS |

`MANA_NONEMPTY` 判断该玩家六类法术力之和，不是单独颜色的值；分开六色时也会一起按总量显隐。隐藏不重新排版，仍保留几何位置。

虽然 Java 枚举存在 `PLAYER_ACTIVE`，它当前用于内部激活装饰，不能作为通用 JSON 隐藏条件。生命、阶段、确认取消、头像、操作入口不能用上述特殊条件隐藏。

## 悬浮文档

`scene.floating` 把实际文档接入窗口内部覆盖层。合法 ID：REPORT_STACK、CARD_PICTURE、CARD_DETAIL、REPORT_LOG、REPORT_COMBAT、REPORT_DEPENDENCIES、DEV_MODE。它不是任意新控件，也不是独立系统窗口。

每项只接受 `bounds`（必填）、`visibleWhen`（默认 ALWAYS，允许 ALWAYS/STACK_NONEMPTY）、`draggable`（默认 true，必须是真布尔值）。

这是局部配置示例，加入 scene 后还要从 regions 中移除相同文档：

```json
{
  "REPORT_STACK": {
    "bounds": [0.61, 0.15, 0.205, 0.46],
    "visibleWhen": "STACK_NONEMPTY",
    "draggable": true
  }
}
```

悬浮 REPORT_STACK 自带标题，所以不能同时放 STACK_STATUS。浮层默认矩形不得覆盖真实手牌区域、REPORT_MESSAGE 或 PROMPT_*；否则校验拒绝。

运行时拖动还尝试避让固定卡图和文字详情区域，寻找最近可用位置。但没有足够空间时，放置逻辑可能退回配置中的初始位置，并不保证避开所有预览；浮层之间也不是完整碰撞求解系统。应预留真实空位，测试长堆叠和小窗口，不宣称“任何情况下绝不遮挡”。

手牌和响应区要使用明确的 hands/HAND_*、REPORT_MESSAGE 或 PROMPT_* 标识，不要把关键区域藏在 remaining 里，导致保护区域推断不明确。

拖动位置仅保留在当前场景实例中；重新加载、重建场景或重启可回到默认 bounds，不写入跨重启位置配置。ACTIONS_MENU 打开的是另一种非模态系统窗口，可拖动、调整大小、按 Escape 或关闭按钮收起，生命周期随场景释放。

## 绘制器替换和卡牌展示

`scene.renderers` 将已有 widget 映射到已注册 renderer。通常保持默认，只有 ZONE_* 可改为 ZONE_PILE 或 ZONE_BUTTON，用于牌堆缩略图和文字按钮。ZONE_PILE/ZONE_BUTTON 本身不能作为独立 widget ID。

v1～v3 的 PHASES_ACTIVE 不能替换 renderer；v4 新客户端支持 `"PHASES_ACTIVE": "PHASES_SPLIT"`，上半 FIELD_1、下半 FIELD_0，各自保留真实阶段控件。仅支持两个字段、最多一副可见手牌，否则回退。详细约定见 [v4 扩展](DesktopMatchSkin-v4.zh-CN.md)。CUSTOM_* 需要先由可信主程序 Java 注册；单靠 JSON 写出一个名称不会自动实现功能。

`cards` 只允许：

| 字段 | 合法值 | 默认 |
| --- | --- | --- |
| `hand` | classic / fan | classic |
| `fanDegrees` | 有限数字 0～60 | 28 |
| `battlefield` | classic / lanes；新版 v4 另支持 adaptive | classic |
| `overlay` | classic / badges | classic |

fan 的绘制与点击共享旋转几何，角度为整体展开角度。旧手牌“不重叠”“每行张数”不参与 fan 计算。lanes 将完整牌叠分区排列，附着物和合并牌叠仍作为整体；空间不足时滚动。badges 使用当前许可的展示数据绘制指示物等，不获得隐藏牌权限。

新版 v4 的 `adaptive` 将地与非生物非地永久物放在同一后排的左右半区，独立换行且不越左右中线；生物保留全宽居中行。超宽完整牌叠等比缩小，纵向溢出保留滚动。兼容要求见 [v4 扩展规范](DesktopMatchSkin-v4.zh-CN.md)。需要工具 1.1.5 对应的新客户端，不能用于 v3；静态通过不等于实机通过。

拖手牌至任一可见战场后释放，调用原控制器的选牌/出牌流程；不是直接移动 Card 模型，不自动把落点永久物设为目标。非法施放、费用与目标仍由原引擎判断。

## 可完整解析的最小双人骨架

以下是完整 v3 JSON，不是局部片段。它没有外部图片和字体，适合用来验证解析与文档分配；不是视觉成品，小窗口的窄提示区和薄玩家条仍需另行设计验收。新客户端制作优先复制已经升级为 v4 的暮辉秘境，并配合 v4 扩展规范；旧客户端制作保留本骨架，不得混用 v4 字段。

```json
{
  "version": 3,
  "id": "minimal-two-player",
  "regions": [
    {"bounds": [0, 0, 0.8, 0.3], "documents": ["opponents"], "split": "ROWS"},
    {"bounds": [0, 0.4, 0.8, 0.3], "documents": ["FIELD_0"]},
    {"bounds": [0, 0.8, 0.8, 0.2], "documents": ["hands"], "split": "COLUMNS"},
    {"bounds": [0.8, 0.8, 0.2, 0.2], "documents": ["REPORT_MESSAGE"]},
    {"bounds": [0.8, 0, 0.2, 0.8], "documents": ["REPORT_STACK", "CARD_PICTURE", "CARD_DETAIL", "BUTTON_DOCK", "remaining"]}
  ],
  "scene": {
    "widgets": {
      "FIELD_1.AVATAR": [0, 0.3, 0.2, 0.05],
      "FIELD_1.DETAILS": [0.2, 0.3, 0.6, 0.05],
      "PHASES_ACTIVE": [0, 0.35, 0.8, 0.05],
      "FIELD_0.AVATAR": [0, 0.7, 0.2, 0.05],
      "FIELD_0.DETAILS": [0.2, 0.7, 0.6, 0.05]
    },
    "surface": {"border": false, "background": false, "title": false}
  },
  "cards": {"hand": "fan", "fanDegrees": 22, "battlefield": "classic", "overlay": "classic"}
}
```

该例特意把独占提示区放在含 remaining 的兜底 region 之前，防止 remaining 先吞入 REPORT_MESSAGE。选择器按配置顺序执行，而不是按屏幕从上到下的位置执行。交付 AI 生成的配置时，必须运行下面的 `arrange` 检查，不能只看 JSON 外形。

本文发布前已用整合版的实际 `MatchUiLayout` 验证此完整骨架及暮辉秘境原件：双人零/一副手牌、开发模式、稀疏手牌编号均能分配文档；多副手牌及 3/4/8 玩家能被容量检查识别为需要回退。该结果不是实机视觉或交互验收。

## 路径和设置持久化

以 `ForgeConstants.USER_PREFS_DIR` 为根，不写死开发者电脑路径：

- `desktop-match-skins/<id>-<随机后缀>/`：导入副本。
- `desktop-match-ui.properties`：选中提供者和包名。
- `match.xml`：经典布局。
- `match-ui-<摘要>.xml`：非 scene 的自定义区域布局保存位置；scene 使用 JSON 固定坐标，不按普通拖拽布局持久化。

导入副本是持久化数据，不依赖原 ZIP 的后续位置；菜单每次打开从磁盘刷新，并按具体副本标记选中状态。选择写入同目录临时文件后原子替换偏好文件，失败不破坏旧选择。删除入口需要明确确认；若删除当前副本，先持久化经典布局并卸载当前场景，再在后台删除文件。删除只接受皮肤库内的单级合法名称，拒绝符号链接、目录联接和路径越界；不删除原 ZIP、内置资源、其他副本或游戏数据。删除是永久的；失败会报错，不宣称“已删除”。直接改过安装副本的作者应先备份原稿。

Windows 默认通常是 `%APPDATA%/Forge/preferences/`，Linux 通常是 `~/.forge/preferences/`，macOS 通常是 `~/Library/Application Support/Forge/preferences/`；自定义 `userDir` 会改变位置，以运行实例配置为准。

导入后修改原 ZIP 或原稿不会同步安装副本。迭代可重新导入，或备份后修改已安装副本再“重新加载界面配置”。不建议 AI 为演示直接篡改用户正式偏好，优先独立测试用户目录。

## 验证和交付标准

### 静态与真实解析验证

检查 JSON 语法、未知字段、键类型、ID、资源路径、大小、颜色、整数范围、所有固定矩形、功能组合与缺失文档。JSON Schema 或自写矩形脚本可以辅助，但不能代替真实 Java 解析器。

至少调用 `MatchUiLayout.read(reader, skinDirectory)`，然后为实际文档集合调用 `layout.scene().supports(documents)` 和 `layout.arrange(documents)`。支持检查与分配检查是两个步骤。

典型双人文档集合为 FIELD_0、FIELD_1、HAND_0，加上 REPORT_STACK、REPORT_COMBAT、REPORT_LOG、REPORT_DEPENDENCIES、REPORT_MESSAGE、BUTTON_DOCK、CARD_PICTURE、CARD_DETAIL；开发模式再加 DEV_MODE。另测没有 HAND 的观战、稀疏 HAND_2、多副可见手牌、3/4/8 玩家。无法支持的组合应验证会回退，而不是假报支持。

ZIP 还须实际走一次导入流程；目录能解析不代表压缩包的根层、条目命名和限制正确。安装错误测试必须确认旧包未被破坏。

仓库根目录可运行以下专项测试，PowerShell 下给带逗号参数加引号：

```powershell
mvn -B -pl forge-gui-desktop -am test "-Dtest=MatchUiLayoutTest,MatchSceneLayoutTest,MatchSkinPackageTest,HandDropTest,StackDescriptionLocalizationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

现有测试验证原有示例，不会自动扫描你的所有新皮肤；新作品必须新增用例或显式将新目录传给验证器。若改了 Java，除专项测试外应执行完整桌面依赖测试和平台范围守卫，必要时检查 Android 编译；不得为赶进度删除已有测试或跳过失败用例。

### 手工验收矩阵

| 场景 | 验收要点 |
| --- | --- |
| 经典与新皮肤往返 | 原字体、颜色和控件恢复，没有重复监听或残留窗口 |
| 起手、调度、抽牌、弃牌 | 真手牌持续可见，卡牌点击和预览正确 |
| 出地、施放、支付、取消 | 原确认取消可达，支付不被装饰层拦截 |
| 战斗与多目标 | 横置、附件、牌叠、指向线和选择事件正确 |
| 堆叠空与非空 | 条件显示正确，长文本和多个对象可操作 |
| 法术力零与非零 | 同一玩家各色支付控件按预期显示 |
| 各区域入口 | 坟墓场、放逐区、指挥官区等不丢失权限和入口 |
| 观战、多人、控制变化 | 不泄露隐藏牌，不遗漏玩家，不支持时回退 |
| 窗口缩放与高 DPI | 小窗口、常用窗口、最大化及 125%/150% 等实际缩放下文字可读 |
| 浮层拖动 | 不遮住关键操作，背景不留残影，窗口边界正常 |
| 对局结束与重开 | 窗口、定时器、组件所有权清理正常 |

截图只能说明当时的画面，不能证明网络、权限、所有分辨率和所有牌局均正常。性能声明需要真实测量，不用“16 ms 拖动合并”推导出稳定 60 FPS。

### 交付清单

- 可导入 ZIP 与对应可编辑源目录，文件内容一致。
- README：最低接口版本、安装入口、支持人数、默认回退、已知限制。
- ASSETS 和字体许可：来源、作者或授权依据，AI 生成素材如实标记。
- 测试记录：所用程序版本/提交、窗口大小、系统缩放、执行命令与结果，区分自动检查和人工验收。
- 列出未验证项目，不自行发布、推送、更新版本号或修改服务器。

## 可直接交给 AI 的任务文本

```text
请按随附的 Forge 桌面对战皮肤 AI 制作规范制作一个 match-ui v3 数据皮肤。
先阅读目标项目实际解析器与 skins/dusk-sanctum 示例，再复制为新的独立目录。
仅修改新目录的 JSON、图片、字体及说明，不改规则、网络、Android、翻译或更新渠道。
我的风格要求是：在这里填写。
我的目标人数、窗口大小和缩放比例是：在这里填写。
必须保留真实手牌、阶段、生命、法术力、确认取消、区域与全部对战操作入口。
先验证 JSON、资源、几何、容量和完整文档分配，再打包导入测试。
没有实机环境就明确说明，不能声称交互验收通过。
交付 ZIP、源目录、README、素材来源/字体许可、测试结果和已知限制。
不替换我的正式安装、不修改用户设置、不推送、不部署。
```

## 仅在用户要求扩展主程序时

可使用 `DesktopMatchUi.register` 注册可信布局提供者，或 `MatchWidgetRegistry.register` 注册可信 Java 控件工厂。JSON 只引用已注册名称，不装载任意类。

工厂通过 Context 获得 match、field、type、theme；Widget 提供 component、refresh、dispose。全局控件的 field 为 null。必须在 Swing EDT 操作实际组件，不读隐藏牌模型，不绕过 `ZoneAction` 和原控制器。释放阶段恢复样式、移除监听、停止定时器和关闭窗口，不在 refresh 中反复创建未释放对象。

新组件应扩展字段校验、可见性与容量判断、清理生命周期及测试，并重新编译主程序。不要悄悄改变旧配置含义；如修改不兼容，应提供明确版本策略与迁移说明。数据制作者遇到能力不足时，应报告需求而不是假造配置键。
