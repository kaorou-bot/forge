# 桌面对战皮肤 v4 扩展规范与制作指南

本轮扩展仅适用于桌面对战画面。需要支持 `match-ui` **version 4** 的新客户端；已发布的 cn0930 只支持 v1～v3，不能导入 v4 包。客户端产品版号与皮肤格式版本不是同一件事。本轮未改 Android、联机协议、规则引擎或服务器。

v1/v2/v3 保留旧行为。新工具 1.2.0 同时校验 v3/v4。《制作入门》《AI 制作规范》描述基础布局、必需操作与资源限制；制作 v4 时以本文的新增字段为补充，不要把新字段放进 version 3 配置。

## 1.2.0 补充：制作效率与可选接口

参考能力修订号 `2026-10-01.1`。能力 JSON 随客户端嵌入，也随工具提供；产品版号或 `version:4` 不足以判断较旧测试客户端是否支持新字段。

- `cards.handCardWidthMax`：fan 手牌宽度上限，整数 16～300，默认 300。小区域仍会缩小；classic 模式不接受该字段，防止静默无效。
- `scene.visibility` 可对 `FIELD_n.STATUS` 设置 `STATUS_NONEMPTY`：玩家没有指示物时隐藏，指示物出现后恢复；不会重新排布邻居，不能删除必需的 STATUS 注册。
- `scene.appearance.styles` 可用 `text.REPORT_LOG`、`text.REPORT_STACK`、`text.CARD_DETAIL` 等设置该文档正文。未指定则回退 `text`；影响真实文本及后续新增文本，并在标签切换时恢复。支持 fontSize、textColor 等文本属性，icon/textBounds/textAlign 不控制 HTML 文本排版。
- 新工具的 inspect 输出实际样式名、padding、内框、分区阶段尺寸及手牌估算；runtime 可选调用配套 JRE 与 JAR 的生产诊断入口，不编译、不注入 JAR、不启动对局。

独立包的 `docs/高效制作与排错.md` 有完整流程与公式。示例生成器会设计自己的图标配色，不能用作迁移；自定义旧皮肤使用 `skin_tool.py migrate`。

推荐示例为新版客户端内置的「暮辉秘境」。源目录 skins/dusk-sanctum 与独立资料包 examples/dusk-sanctum 同源；新版菜单直接选择即可。旧「竞技场布局（示范）」和「桌面对战场景（预览）」入口已移除，容量不足时的内部安全回退保留。修改示例时复制并改 id，再打包导入；不会改写程序内置原件或其他已安装皮肤。

## 人类制作者：从示例开始

独立资料包中 `examples/v4-workbench` 是可编辑原稿，`ready-to-import/v4-workbench.zip` 可以直接导入新版测试客户端。它使用几何占位图标展示接口，不是正式美术成品。

1. 先导入示例，确认可以发牌、施放、选择目标和切换经典布局。
2. 替换 `images/library.png`、`graveyard.png`、`exile.png`：改变区域图标，文字中的数量继续随对局更新。
3. 替换 `life.png`：改变生命图标，生命数字不是画在图片上的固定数字。
4. 修改 `PHASE.MAIN1` 等样式的 `icon`：每个阶段可使用不同图片。示例共用 `phase.png` 作为占位。
5. 修改 `avatar.shape` 和 `avatar.frame`：裁切真实头像，并在其上绘制透明 PNG 边框。
6. 修改 `floating.CARD_DETAIL.fill` 的末两位透明度：只改变说明窗底板，文字仍保持清晰。
7. 改 `cards.battlefieldAlign` 为 `CENTER`，生物整行居中；`START` 则从左侧排列。
8. 将 `cards.battlefield` 设为 `adaptive`：地牌在左半区，非生物非地永久物在右半区，各自换行且不越左右中线。需要本资料包 1.1.5 对应或更新的桌面客户端。

示例可重新生成：

```text
python tools/make_v4_example.py --base examples/dusk-sanctum --out output/my-v4-skin
python tools/skin_tool.py validate output/my-v4-skin --json
python tools/skin_tool.py preview output/my-v4-skin --out output/my-v4-layout.html
python tools/skin_tool.py pack output/my-v4-skin --out output/my-v4-skin.zip
```

输出必须是新目录/文件，工具不覆盖已有作品。制作、静态校验和打包不需要 Forge、源码或 JDK；实际点击和对局测试仍需新版客户端。

## AI／开发者：图片描述

v4 中 `background`、样式的 `image/icon/frame` 和装饰的 `image` 接受路径字符串，或下面的图片对象：

```json
{"path":"images/button.png","mode":"NINE_SLICE","slices":[6,6,6,6]}
```

- `path`：必须为包内 PNG/JPG/JPEG 路径，仍禁止网址、绝对路径、越界路径。
- `mode`：`STRETCH` 拉伸（默认）、`CONTAIN` 等比完整显示、`COVER` 等比填满并裁切、`TILE` 按源像素平铺、`NINE_SLICE` 九宫格。
- `slices`：仅九宫格使用，四个整数分别为源图的上、右、下、左边宽。每项 0～16384；上下之和小于源图高度，左右之和小于宽度，中心不能为空。
- 字符串等同于 `STRETCH`。九宫格边角在常规尺寸下保持源像素大小；控件小于两侧边宽之和时，边角按比例缩小，不会反向绘制。

原有包体积、解码像素总量、路径和导入事务限制继续生效。所有图片（包括状态图片、图标、边框、装饰）共用资源缓存与像素预算。

## 样式新增字段

放在 `scene.appearance.styles` 的具体样式对象中。原有 `fill/border/highlight/radius/padding/fontSize/image` 继续可用。样式类别不是 CSS，不支持任意选择器或继承语法。

| 字段 | 类型和范围 | 用途 |
| --- | --- | --- |
| shape | RECTANGLE / ROUNDED / CIRCLE / ELLIPSE / HEXAGON / DIAMOND | 面板轮廓；头像内容按相同轮廓裁切 |
| opacity | 数字 0～1，默认 1 | 底板透明度，不使文字一起变透明 |
| borderWidth | 数字 0～16，默认 1 | 边框宽度 |
| icon | 图片描述 | 控件自定义图标；区域控件使用后不再绘制真实顶牌缩略图 |
| iconBounds | `[x,y,w,h]` | 图标在控件内部的比例位置，默认 `[0,0,1,0.65]` |
| textBounds | `[x,y,w,h]` | 文字在控件内部的位置；有图标时默认 `[0,0.65,1,0.35]`，无图标时占整个控件 |
| textColor | #RRGGBB / #RRGGBBAA | 控件文字颜色 |
| textAlign | LEFT / CENTER / RIGHT | 文字水平对齐，默认 CENTER |
| showText | 布尔值，默认 true | 是否绘制控件文字；不要隐藏生命和区域数量 |
| frame | 图片描述 | 内容上方的装饰框，通常为中心透明 PNG |
| states | 对象 | 状态覆盖，见下节 |

内部坐标与布局 bounds 一样要求有限数值、不越界、正宽高。简单按钮、生命、区域、阶段使用单行实时文字；它不是任意富文本排版系统。卡牌规则等多行信息继续由原文本组件排版。字号变大后应同时检查可用空间。

半透明颜色和 opacity 会相乘。若 `fill` 已是半透明色，无须再设置相同程度的 opacity。frame 和 icon 保持自身图片透明通道；opacity 用于底板，不是整个组件的透明度。

### 控件状态

```json
{
  "fill":"#152D42",
  "border":"#91D0E0",
  "fontSize":15,
  "states":{
    "normal":{"fill":"#152D42"},
    "hover":{"fill":"#285875","textColor":"#FFFFFF"},
    "pressed":{"fill":"#0D1C2B"},
    "selected":{"border":"#FFE298"},
    "disabled":{"opacity":0.35,"textColor":"#888888"}
  }
}
```

每个状态覆盖当前样式的基础字段，不修改控件业务状态。优先级为 disabled → pressed → hover → selected → normal。未定义的状态使用 normal，未定义 normal 使用基础样式。每个覆盖对象继承基础样式，**不继承 normal 覆盖对象**；禁止状态内再嵌套 states。明确的状态覆盖不会被旧 highlight 自动着色取代，除非该覆盖自己也声明 highlight。

按钮的状态来自原控件；阶段的 selected 表示当前阶段/让过标记，阶段“停留开关”不是按钮 disabled。停留圆点与让过箭头继续由程序绘制。头像/生命的选中装饰沿用玩家高亮状态。不是每种非交互文本都有 hover/pressed 状态，不要把这些字段当作任意组件事件脚本。

## 区域、生命与阶段图标

可精确定位 `FIELD_0.ZONE_LIBRARY`、`FIELD_1.ZONE_GRAVEYARD`、`FIELD_0.ZONE_EXILE` 等现有控件；也可用 `zone` 类别统一设置。区域图标替换外观，但左键、右键、数量和可见性权限不变。牌库和隐藏手牌不会因自定义图标读取牌面。

生命图标用 `life` 类别或 `FIELD_n.LIFE`。常见横排写法：

```json
{
  "icon":{"path":"images/life.png","mode":"CONTAIN"},
  "iconBounds":[0,0.1,0.32,0.8],
  "textBounds":[0.32,0,0.68,1],
  "fontSize":28
}
```

阶段独立样式键是 `PHASE.<枚举名>`，找不到时回退 `phase` 类别：

`UPKEEP`、`DRAW`、`MAIN1`、`COMBAT_BEGIN`、`COMBAT_DECLARE_ATTACKERS`、`COMBAT_DECLARE_BLOCKERS`、`COMBAT_FIRST_STRIKE_DAMAGE`、`COMBAT_DAMAGE`、`COMBAT_END`、`MAIN2`、`END_OF_TURN`、`CLEANUP`。

例如 `PHASE.COMBAT_DECLARE_ATTACKERS` 配攻击图标。这个样式键不是新增 widget；仍保留一个 `PHASES_ACTIVE` 布局节点。阶段条换到另一位玩家时，图标和状态样式也随原控件正确应用及恢复。

### 上下分区阶段按钮（1.1.3 资料包对应客户端起）

在 `scene.renderers` 中添加 `"PHASES_ACTIVE": "PHASES_SPLIT"`。每个阶段是一枚上下分区按钮，上半对应 FIELD_1，下半对应 FIELD_0。普通双人对局是上方对手、下方自己；旁侧文字会根据实际本地控制身份标记，观战或双方均受控时标记“上方/下方”，悬停显示完整玩家名和阶段说明。它不是点击后切换玩家的按钮。

左右键作用于所点半区的原阶段控件：左键切换停留，右键切换让过目标；两个玩家的设置互不覆盖。圆点表示停留开启，金色小箭头表示让过目标。分区模式中 selected **仅表示当前回合的当前阶段**，金色左侧短线在悬停期间也保留；不会因让过标记或优先权转移而假装进入该阶段。经典/默认单行模式继续保留原行为。

两半共用 `phase` / `PHASE.<枚举名>` 样式。背景与 frame 按整枚按钮绘制后裁切，文字、iconBounds、textBounds 在各自半区内计算。默认使用简短阶段名，悬停可看全称；不要用两行按钮共用一张文字图片。请预留至少约 44 像素整条高度，最低目标分辨率下逐半检查文字和点击区域。暮辉秘境预留视口高度 6.4%，不遮挡上下战场。

只能用于恰有 FIELD_0、FIELD_1 且最多一副可见手牌的场景；其他人数/多手牌安全回退。省略 renderer 保持原先的当前回合单行阶段条。旧客户端不认识 PHASES_SPLIT，须使用本次或更新的测试客户端；切换皮肤/经典布局会恢复原标签、提示和组件归属，但保留玩家刚修改的停留/让过状态。

## 头像框

推荐用独立的 `FIELD_n.AVATAR_IMAGE`，不要为换框重新绘制静态玩家头像。

```json
{"shape":"HEXAGON","border":"#8CD7E6","borderWidth":2,"frame":"images/frame.png","padding":4}
```

`CIRCLE` 使用居中正圆，`ELLIPSE` 使用整个矩形椭圆。六边形、菱形按控件宽高生成。真实头像裁切后再绘制 frame，目标选择仍使用原头像组件。形状只影响绘制，点击区域保留原矩形，以免缩小选目标的可点击面积。边框 PNG 的透明中心必须与所选形状匹配。

## 半透明文字浮窗

将 `CARD_DETAIL` 或 `REPORT_LOG` 从固定 regions 中移出，放入 `scene.floating`；不能重复分配同一个文档。

```json
"CARD_DETAIL":{"bounds":[0.83,0.40,0.165,0.29],"draggable":true}
```

在 `appearance.styles` 中为 `floating.CARD_DETAIL` 设置 `fill: "#10202D99"`、边框、圆角、padding 等。`floating.<文档ID>` 精确匹配优先，未声明时使用 `floating` 类别。正文使用 `text` 样式；v4 文本底层透明，窗体底板提供半透明背景，文字不整体淡化。

暮辉秘境推荐示例现采用全透明底板：`fill: "#101D2900"`，正文 `textColor: "#F5F8FA"`、字号 15。日志/战斗/关联牌的 `scene.surface.background: false` 会持续作用于后来挂载的面板与 JLayer 内的滚动容器，切换回经典布局时恢复原装饰；不改变卡图、缩略图或按钮本身。

浮窗仍在游戏窗口内，可通过标题拖动；不会改变文字来源或卡牌可见性。初始位置不能覆盖手牌和响应按钮，拖动继续避让保护区域。不要用它替换必须一直可操作的确认／取消入口。不提供独立于游戏窗口的透明桌面悬浮窗。

## 多层装饰

`scene.appearance.decorations` 最多 64 项：

```json
[{"image":{"path":"images/crest.png","mode":"CONTAIN"},"bounds":[0.4,0.3,0.2,0.1],"rotation":15,"opacity":0.25,"plane":"FOREGROUND","order":10}]
```

- plane 为 BACKGROUND（默认）或 FOREGROUND。背景装饰在真实区域后面；前景装饰在战场上方、独立控件和浮窗下方。
- order 为 -1000～1000 整数，同一层中从小到大绘制；相同 order 保持声明顺序。
- rotation 为 -360～360 度；旋转结果裁切在该装饰 bounds 内。
- opacity 为 0～1，仅对该装饰生效。
- 装饰不拦截鼠标，不读取游戏模型；前景对手牌、提示及固定预览保护区域裁切避让。美术仍可能遮盖其他战场内容，建议低透明度并实际验收。
- 按窗口尺寸缓存渲染结果。没有逐帧动画、脚本、任意控件 z-index 或媒体查询。

## 卡牌排列与角标

在顶层 `cards` 中添加。仍需 `hand:"fan"`、`battlefield:"lanes"` 或 `battlefield:"adaptive"`、`overlay:"badges"` 才分别启用对应效果。

| 字段 | 范围／默认 | 行为 |
| --- | --- | --- |
| handSpacing | 0.1～1.5 / 0.8 | 以牌宽为基准的最大间距，牌多时压缩到视口内 |
| handArc | 0～1 / 1 | 扇形弧线高度比例 |
| hoverLift | 0～0.4 / 0 | 悬停抬升占牌高的比例；几何预留抬升空间 |
| fanDegrees | 0～60 / 28 | 沿用 v3 的整体扇形角度 |
| battlefield | classic / lanes / adaptive；默认 classic | adaptive 是新版 v4 专用的左右分区自适应战场 |
| battlefieldAlign | CENTER / START；v4 默认 CENTER | 生物行水平居中或从左排列 |
| battlefieldRowGap | 整数 0～80 / 8 | 战场行间距 |
| battlefieldGap | 整数 0～80 / 6 | 整副牌叠之间的间距 |

v4 lanes 使用全宽独立行：生物居中，地和其他永久物另行排列，己方与对方的生物／地行顺序镜像。多行或大型附着牌叠超出视口时保留滚动。牌叠本身不拆开；不会改变永久物所在区域或游戏规则。v3 lanes 原有右侧其他永久物列保持不变。

### 新增 adaptive：后排左右分区

将原稿中的 `cards.battlefield` 改为 `"adaptive"` 后重新打包导入。暮辉秘境内置示例和工具包示例已启用；以前导入的副本不自动覆盖。旧 v4 客户端不认识这个值，请先升级到 1.1.5 资料包对应的测试客户端；兼容旧版时保留 `lanes`。

- 生物使用完整战场宽度，仍受 battlefieldAlign 控制，靠近双方战场交界处。
- 后排的地牌在左半区，非生物非地永久物在右半区并右对齐。两组同一高度开始，分别根据可用宽度换行。
- 中线指每位玩家战场视口的左右分界，不是双方玩家的上下交界。空的一半不会借给另一组，中间留出间隔。
- 对手只镜像前后排的纵向顺序，不交换左右：地仍在屏幕左半区，其他永久物仍在右半区。
- 分类以当前牌面为准，生物优先于地；生物地进入生物行。灵气、装备随原牌叠布局，不拆散附着关系。
- 单副牌叠宽于所在半区时整副等比缩小，包含横置旋转包围框、附着偏移和实际点击位置；普通牌叠不额外缩小。窗口放大后按原始尺寸重新计算，不累计缩小。
- 牌多时纵向滚动，不越过中线或让两类牌重叠。极窄窗口下牌可能很小，可放大窗口或使用原有卡牌预览。

新规则不修改经典布局、旧 lanes、游戏规则、卡牌分组设置或网络同步。

hoverLift 同时更新绘制和点击命中；抬起后仍保留原位置的命中范围，避免鼠标未动却反复进出。拖动中不强制改写拖动卡的位置。

`cards.badges` 可含 `powerToughness/counters/damage`；每个对象接受 `bounds`（相对单张卡）、`fill`、`text`（颜色）、`fontSize`（8～32，默认12）、`radius`（0～32，默认6）。默认位置分别为 `[.5,.82,.5,.18]`、`[0,0,1,.5]`、`[0,.82,.5,.18]`。指示物按名称排序逐行绘制，超出指定框裁切，制作者应给长中文和大量指示物留足空间。数据仍只来自当前允许查看的牌面。

## 验收要求

静态预览仅显示布局占位、背景和装饰位置，不模拟真实头像、状态切换、卡牌几何、字体或透明合成。不要把 HTML 示意当实机通过。

新版专项测试覆盖版本隔离、状态与资源校验、图片模式/九宫格、形状及透明像素、居中牌叠碰撞、手牌旋转/抬升空间、透明文本恢复。还应在测试客户端中逐项检查：

- 两边各放 1、2、多只生物，含灵气、装备和横置永久物；牌叠位置、选目标连线和滚动正确。
- adaptive 下测试大量地、宝物等非生物衍生物、旅法师、附着牌叠与生物地；缩放窗口后后排仍各在半区，点击、横置和滚动正确。
- 自定义区域图标的左键、右键菜单和数量更新；不能泄露牌库或手牌。
- 生命变化、玩家目标选择、阶段停留与让过操作；图标不能遮挡实时状态。
- 头像形状与边框；切回经典布局后没有残留裁切、字体或底板透明度。
- 浮窗文字、拖动后背景恢复、手牌抬升与点击/拖入战场出牌。
- 缩小窗口、不同 DPI、多人回退与网络对局。此次没有增加自适应断点或新多人模板。

通过数据/渲染测试不等于实机对局通过。正式发布前应由玩家验收；不要提前替换线上安装包。
