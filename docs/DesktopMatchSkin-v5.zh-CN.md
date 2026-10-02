# 桌面对战皮肤 v5：窗口适配与制作体验

本次仅扩展桌面对战界面，不改变 Android、游戏规则、网络协议或服务器。v3/v4 皮肤继续可用。cn1002 的目标能力清单为 `2026-10-02.1`，独立资料包为 `2.0.1`（含战场紧凑排列、详情区域和日志/堆叠互斥修正）。旧客户端不能加载 v5；不要仅修改版本数字来“兼容”。

## 玩家怎么用

对局中打开“布局 → 对战界面 → 暮辉星台（v5 功能示例）”。也可导入资料包 `ready-to-import/dusk-observatory.zip`。旧暮辉秘境仍保留。

- 窗口内容宽度不超过 1440 时自动选择紧凑方案；更宽使用基础方案。尺寸变化停下约 250 毫秒后切换，期间不改变游戏状态。
- 3～8 人选择各自的布局，显示每位玩家的独立阶段控制、生命值和区域入口。建议 6～8 人使用 1920×1080 或更大窗口；大量永久物仍会滚动。
- 双人阶段按钮仍是上半对手、下半自己；多人每名玩家独立一行，不共用原控件。
- “皮肤个人设置”调整字号倍率、扇形手牌最大宽度、浮窗背景透明度和装饰透明度，也可优先选紧凑布局。
- 拖动浮窗标题移动，右下角拖动缩放，标题右键收起/展开、锁定/解锁、恢复默认位置。收起只隐藏文档正文，标题仍可操作。
- 个人设置与浮窗位置独立保存，更新作者 ID 相同的皮肤不会丢失。多副可见手牌暂时回退安全布局，不能把回退当作该皮肤已支持多手牌。

个人文件位于 `USER_DIR/preferences/desktop-skin-experience/<skin-id>.json`。默认 Windows 为 `%APPDATA%/Forge/preferences/desktop-skin-experience/`；设置了 `forge.profile.properties` 的 `userDir` 则跟随它。浮窗按布局方案保存归一化坐标，不按窗口逐个生成文件。原 JSON 不被修改。保存使用临时文件与原子替换；损坏文件尝试备份成 `<skin-id>.invalid.json` 后使用皮肤默认值。

## 快速制作

先复制 `examples/dusk-observatory`，修改作者 ID，再改外观和布局。这个 ID 同时是个人设置的命名空间；不同作品请勿复用。

```text
python tools/skin_tool.py validate 我的皮肤 --json
python tools/skin_tool.py inspect 我的皮肤 --width 1280 --height 720 --players 2 --json
python tools/skin_tool.py preview 我的皮肤 --players 8 --out output/eight-player-layout.html
python tools/skin_tool.py pack 我的皮肤 --out output/my-skin.zip
```

可选真实解析诊断（需要配套测试客户端，不需要源码）：

```text
python tools/skin_tool.py runtime 我的皮肤 --java 客户端/runtime/bin/java.exe --jar 客户端/forge-gui-desktop-2.0.15-cn0930-jar-with-dependencies.jar --players 8 --width 1920 --height 1080 --out output/java-eight-player
```

Java 工具输出所选方案、人数、容量、配置/渲染器哈希、几何图和部件样张。几何图不是游戏截图；部件样张也不是完整对局。最终仍需点击、缩放、换肤和真实对局验收。

## 响应式配置

顶层 `version: 5`，新增 `experience`，其余延续 v4。`experience.defaults` 支持：

| 字段 | 默认 | 范围/意义 |
|---|---|---|
| fontScale | 1 | 0.75～1.75，文字字号倍率 |
| handWidth | 300 | 整数 40～300，扇形手牌宽度上限；不扩大超过作者上限 |
| panelOpacity | 1 | 0～1，浮窗背景/纹理透明度倍率，不淡化字形 |
| decorationOpacity | 1 | 0～1，装饰层透明度倍率，不影响交互控件 |
| layoutMode | AUTO | AUTO 或 COMPACT；COMPACT 优先选 ID 为 compact 且人数匹配的方案 |

`experience.variants` 最多 16 项，按声明顺序选首个匹配的方案；都不匹配使用基础布局。每项：

- `id`：小写字母开头，可含数字和连字符，最长 32，不能为 base，不能重复。
- `maxWidth`：内容区逻辑像素，不是整个屏幕；320～16384，默认 16384。
- `minPlayers`、`maxPlayers`：整数 1～8，默认 2，前者不得大于后者。
- 可替换 `regions`、`cards`，以及 scene 的 `widgets`、`anchors`、`floating`、`renderers`、`visibility`。
- 每个提供的字段都是**整段替换，不是逐项合并**。未提供沿用基础段。所有方案共用 appearance，不重复解码字体和图片。
- 所有方案导入时一起验证；隐藏控件仍占原位，不会自动挤压其他控件。

建议先写双人基础布局，再写 compact，然后写固定人数方案。示例中多人方案均声明准确人数，不让 3 人控件误用到 8 人。

## 锚点与尺寸约束

`scene.anchors` 的键必须是已有 widget ID。可设 `horizontal` / `vertical` 为 START、CENTER、END（默认 CENTER）；`width`、`height`、`minWidth`、`minHeight`、`maxWidth`、`maxHeight` 为 0～8192 的逻辑像素整数；`aspectRatio` 为 0～10，0 表示不约束。

widget 的原始 bounds 是保留槽位。锚点只在槽内对齐或缩小，永远不越界扩张。槽位小于最小值时，以不覆盖邻居为先，不能依赖 minWidth 强行撑大布局。小窗口应另写 compact。头像可用 `aspectRatio: 1` 防止拉伸。

## 美术与文字

- 新 `shape: POLYGON` 配合 `polygon: [[x,y],...]`，3～32 个 0～1 坐标点，自动闭合。头像在该轮廓中裁剪；现有 frame 可画外框。不是任意 PNG alpha 蒙版，也不改变点击命中规则。
- 阶段样式新增 `markers`，可给 `stop`、`active`、`yield` 提供与其他图片相同的 texture 定义。位置分别右上、左上、右下，大小 6～14 像素。没有素材继续绘制原标记，不允许因缺图丢失必要提示。
- `scene.appearance.documents` 以文档 ID（REPORT_LOG 等）、PROMPT_MESSAGE 或 default 为键。值支持 `margin` 0～32、`paragraphGap` 0～32、`align` LEFT/CENTER/RIGHT。
- 文本边距作用于 JTextComponent；段落间距和对齐针对 HTML 文档。不是完整 CSS 引擎，普通文本控件不保证段落样式，不支持任意 HTML/CSS 注入。
- 字号和颜色继续使用 `text` / `text.<DOCUMENT>` 样式。alpha 面板与文字分开，半透明背景不会一起降低文字不透明度。
- layered decorations、九宫格、按钮状态、区域图标等沿用 v4；本次未引入动画脚本或任意可执行插件。

## 战场分区与多人

`cards.battlefield: adaptive` 新增 `battlefieldPartition`（0.3～0.7，默认 0.5，表示左侧宽度份额）和 `landsSide`（LEFT/RIGHT，默认 LEFT）。生物仍在靠近对方的独立行，地牌与其他永久物共享后排，分区不跨越分界。改变分区比例后，“中线”变成所配置的分界，不再必然是 50%。

2026-10-02 修正：两排按实际完整牌组高度 + battlefieldRowGap 排列，不再强行隔开半个视口；对手整体靠下、自己靠上，使生物靠近双方交界。选牌宽时计入横置动画的包络，附件/换行略超高时最多额外缩到原尺寸的 65%；大量永久物仍滚动，不会为了全屏显示无限缩小。空分类不占一排。经典布局与 lanes 模式不变。

后续可读性修正：自适应布局不再先经过经典布局的“两排高度”缩放。少量牌从一排的可读尺寸开始，按照实际牌组尺寸拟合；居中的生物和两侧永久物在完整旋转/附属牌边界横向不相交时，可以共用部分纵向空间，同时保持前后顺序。发生碰撞才强制分行。正常高度的稀疏战场应同时满足“无裁切”和“牌面可读”，不能只用没有滚动条作为验收条件；极矮窗口或拥挤战场允许滚动，不无限缩小。

## 2026-10-02 示例布局可读性修正

暮辉星台右上卡图、卡牌详情和其他报告使用共用标签区，占内容高度 55%，不再把详情压缩到 5.5%。右下的日志与堆叠使用同一默认位置：日志 `visibleWhen: STACK_EMPTY`，堆叠 `visibleWhen: STACK_NONEMPTY`，自动互斥显示。该新增全局条件对应 `stack-empty-visibility` 能力，需要修正版客户端，旧客户端不支持。

浮窗隐藏/拆卸时会清理其全局滚动箭头。横条向下箭头表示滚动更多内容，不是展开面板；右下角 `◢` 表示拖动缩放并提供悬浮提示。已有用户浮窗位置仍保留；若旧位置不合适，使用“皮肤个人设置 → 重置个人设置”，不自动抹掉用户定制。

验收除了区域不重叠，还需验证 140～265 像素高战场的真实 CardPanel 旋转包络、附件牌组，以及详情扣除标签和牌名后是否仍有正文空间。

多人方案使用 `renderers.PHASES_ACTIVE: PHASES_OVERVIEW`，这是当前回合玩家/阶段摘要，不搬走任何玩家的原阶段控件。每个 FIELD_n 必须提供 `FIELD_n.PHASES`，复用原玩家控制与回调。其他生命值、头像、法术力、区域等完整性要求仍生效。不能同时用 PHASES_SPLIT 和 FIELD_n.PHASES。

## 可视化制作与安全重载

“皮肤布局制作（几何预览）”提供基础/方案选择，区域编号、8 像素网格、拖动和右下角缩放、最多 50 步撤销、临时应用与导出。浮窗位置由对局中的浮窗交互调整；制作面板主要编辑固定区域和控件保留槽。

无效或重叠的固定布局会撤回；手牌、响应控件始终受保护。临时应用只影响当前界面，不保存为作者作品；点击重新加载恢复磁盘版本。导出只写新 JSON，不覆盖同名文件，也不会复制素材。与原素材一起整理成 match-ui.json 后，用独立工具打包。

重载会在拆除当前 UI 前解析与规划，失败则保持当前 UI，显示错误。它不是整场游戏的撤销功能，不会回退玩家动作。制作面板是几何编辑器，不是逐像素所见即所得；画风、文字尺寸和遮挡仍应在客户端检查。

## 维护与验收

- Java：MatchSkinV5Test 覆盖响应式选择、2～8 人控制完整性、分区、锚点、设置持久化与恢复、HTML 样式恢复、轮廓裁剪。保留原 v3/v4、导入管理、手牌、阶段和战场测试。
- Python：test_v5.py 覆盖示例矩阵、嵌套字段、目标能力和非法值；资料包构建再次运行全部独立工具测试。
- 尚需玩家验收：真实 3～8 人局的读字/点击大小、长中文日志、DPI、跨屏拖动、多个浮窗、玩家控制权改变。不能由静态矩阵或 headless 诊断宣称已经完成实机验收。
- 代码与 UI 变更只在桌面模块和相应资源、文档、皮肤工具范围内；本次不自动提交、推送、发布或触碰在线中继服务。
