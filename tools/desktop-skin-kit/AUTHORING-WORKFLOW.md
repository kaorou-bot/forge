# 高效制作与排错（人类程序员 / AI 共用）

依据工业机架皮肤十轮制作记录整理。目标是先发现接口与尺寸问题，再投入美术，避免靠修改 JAR、猜 Swing 内部行为来试错。

## 1. 先确认能力，不猜版本号

资料包版本、`match-ui.version`、Forge 的 `cnXXXX` 产品版本是三个不同的东西。同为 v4 的早期测试包，不一定支持上下分区阶段栏或 adaptive。`tools/skin-capabilities.json` 是本包的参考能力清单，不证明使用者的旧客户端具有这些能力。

```text
python tools/skin_tool.py capabilities --out output/reference-capabilities.json
python tools/skin_tool.py validate work/my-skin --json
python tools/skin_tool.py inspect work/my-skin --width 1920 --height 1028 --out output/1920.json --json
python tools/skin_tool.py inspect work/my-skin --width 1280 --height 720 --out output/1280.json --json
```

`required_features` 列出该作品所需能力。若有使用者提供的**目标客户端**能力文件：

```text
python tools/skin_tool.py validate work/my-skin --target-capabilities target-client-capabilities.json --json
```

缺少能力会失败，不会偷偷改成 classic、删功能或降低配置版本。没有目标文件时标记“兼容性待验收”，不要把本包的清单冒充目标客户端清单。

新版客户端也可以导出实际能力（只需它自带的 JRE，不需 JDK、源码、Maven）：

```powershell
& 'C:/Forge/runtime/bin/java.exe' -cp 'C:/Forge/forge-gui-desktop-version-jar-with-dependencies.jar' forge.screens.match.layout.SkinAuthoringProbe capabilities
```

路径仅为示例，JAR 文件名和 JRE 目录要按解压的测试客户端填写。旧客户端找不到该入口时，应换新版客户端，而不是反编译或注入 helper class。

## 2. 工作目录与升级

```text
work/my-skin/         最终进入 ZIP 的 JSON、图片、字体、许可
source-art/           PSD、SVG、生成脚本等原稿
backup/              版本备份
output/              ZIP、报告、预览
```

第一次制作：复制 `examples/dusk-sanctum`，保留素材许可，修改副本 id。不要在皮肤目录放 `.json.r3bak`、Python 脚本、旧 ZIP。校验器刻意检查所有文件，不会悄悄漏打包。

升级自己的 v3 作品：

```text
python tools/skin_tool.py migrate work/my-v3-skin --out output/my-v4-base.zip --json
```

迁移只把 version 从 3 改成 4，保留 id、布局、样式、图片和所有其他文件字节；不启用新特性、不更换颜色或图标。JSON 缩进会规范化。v4 使用增强绘制器，因此仍需视觉检查，不能承诺像素完全不变。解压到新工作目录后再逐项添加 v4 能力。

`make_v4_example.py` 是接口工作台**示例生成器**，不是迁移器。它会设计自己的配色与图标，所以现在只接受内置 dusk-sanctum 模板，拒绝拿自定义作品当 `--base`。

## 3. 尺寸诊断怎么读

所有坐标都是 Forge 内容区的比例；不是包含标题栏的整屏截图像素。高 DPI 下应使用 Swing 逻辑像素。1920×1080 的桌面不等于 1920×1080 的内容区。

`inspect` 输出每个控件的外框、实际命中的样式名、padding 和内框大小：

```text
innerWidth  = outerWidth  - 2 × padding
innerHeight = outerHeight - 2 × padding
```

padding 未设置时默认 2。样式解析为“精确 ID → 分类 → default”，是**整条替换，不是逐字段继承**。例如写了 `FIELD_0.LIFE` 后，缺失的 padding 不会从 `life.padding` 继承，而取默认值。状态 override 才会与当前样式基底合并。

### 扇形手牌

`cards.hand=fan` 的牌宽取宽度、高度、调用方上限与配置上限的最小值。高度计算不能漏掉旧有的 `H/2.1` 约束：

```text
heightLimit = H / 2.1
若 hoverLift > 0:
  heightLimit = min(heightLimit, max(1, H-6)/(hypot(1,1.4)+1.4*hoverLift))
cardWidth = max(1, min(runtimeMax, handCardWidthMax, floor(min(W/hypot(1,1.4), heightLimit))))
cardHeight = round(cardWidth * 1.4)
```

`handCardWidthMax`：新客户端可选，整数 16～300，默认 300，只支持 fan。它是**上限而非固定宽度**。例如设置 90 就能让较高手牌区中的牌不超过 90px，而不必缩短区域来勉强控制。区域宽度不足时牌仍会缩小或重叠。

`inspect.hand_estimates` 假设单手牌且区域就是 viewport；真实边框、滚动条、分割单元可能减少可用空间。不要拿静态估算充当现场组件测量。

### 战场

battlefield 的初始尺寸预算和手牌不同：高度按两行预留，同时受最小/最大牌宽约束。adaptive 还会把超宽的完整牌叠（含横置和附件）缩入所属半区。因此“战场牌宽只与高度有关”“加宽永远没用”都不准确。

不要把 creatures、lands、other 三类各自画成矩形后就声称验证了真实布局。下面的参考诊断器会调用主程序自己的策略，提供两边战场 6 生物 / 8 地 / 4 其他的样本；真实叠放、操作和复杂数量仍需对局验收。

### 分区阶段按钮

`PHASES_ACTIVE` 的外框扣除 padding 后，还需扣除左侧归属说明 38px、间隔 4px、11 个 3px 间隔，再平分成 12 个 chip。单半区高度约为内框高度的一半；内框总高建议至少 44px（建议，不是解析硬限制）。

| 内容 | 坐标基准 / 行为 |
| --- | --- |
| 背景 `image`、边框 `frame` | 整个 chip 高度；上/下控件只裁出自己的一半 |
| `iconBounds`、`textBounds` | 每个半区自己的坐标；不是整条阶段栏 |
| 圆点 | 本半区设置停留；不是禁用态 |
| 左侧金色线 | 当前阶段与当前玩家 |
| 右侧小箭头 | 让过目标；与当前阶段独立 |
| `states.selected` | 分区模式中用于当前 active 半区；hover/pressed 可覆盖该状态 |
| `states.disabled` | 不用于“取消停留”；阶段按钮仍必须能点回来 |

同一底图如果画了上下两条灯槽，要按整个 chip 放在正确位置。不要把半区文字的位置同时当作整张贴图的坐标。超出控件范围的外发光会被裁切；本版没有新增越界绘制能力。

## 4. 有文字，不一定是同一种控件

- `NAME`、`LIFE`、普通按钮使用 label 类视觉；可用 icon、textBounds 等。
- `PROMPT_MESSAGE` 是真实滚动 HTML 文档，不是用 `paintContent` 模拟的一行居中文字。fontSize/textColor 可用，textBounds/textAlign/showText 不控制它的 HTML 排版。
- 浮窗 `floating.REPORT_LOG` 等样式负责外壳/标题，不等于正文样式。
- 正文全局用 `text`。新版可按文档用 `text.REPORT_LOG`、`text.REPORT_STACK`、`text.CARD_DETAIL` 等精确覆盖；不存在时回退 `text`，换标签页时重新应用并恢复旧样式。
- `STATUS` 是玩家指示物文字，不是“状态”占位字。可设置 `scene.visibility.FIELD_0.STATUS=STATUS_NONEMPTY`，空时隐藏整个栏；必需的 STATUS 控件仍须留在配置里。
- MANA 想保留空槽就不设置 MANA_NONEMPTY（默认 ALWAYS）。条件隐藏不让其他绝对定位控件自动挤过来。
- HEXAGON 拉伸到内框。正六边形需内框 w/h≈1.1547；frame 图片 CONTAIN 不会改变头像剪裁路径。本程序已对真实头像做形状裁剪，不应另外注入补丁。

这些新增能力需要能力清单包含 `hand-width-cap`、`status-nonempty` 或 `document-text-style`，不能只看 version=4。

## 5. 可选：用真实程序算法检查，不编译、不修改 JAR

纯资料包足够制作、诊断、打包。若另有与本包匹配的桌面测试包，可以运行：

```text
python tools/skin_tool.py runtime output/my-skin.zip --java "C:/Forge/runtime/bin/java.exe" --jar "C:/Forge/forge-gui-desktop-version-jar-with-dependencies.jar" --width 1920 --height 1028 --out output/runtime-r1 --json
```

输出是新目录，不能已存在。Python 先做安全校验，再把 ZIP 临时解压；以独立进程启动只读诊断入口，不改用户偏好、不启动游戏、不联网。标准库执行参数数组，不拼 shell 命令，不往 JAR 塞 class。内含的源脚本不会被执行。

| 输出 | 证明什么 / 不证明什么 |
| --- | --- |
| `report.json` | 真实 Java 解析、图片与字体解码、生产几何策略样本坐标；含配置与 renderer SHA-256，明确对应哪份程序 |
| `geometry.png` | 区域、控件、浮窗与样本卡牌的诊断图；不是实机截图，不伪造卡图、提示或玩家头像 |
| `components.png` | 按钮等绘制原语的 5 态样例，以及实际 PhaseLabel 的分区绘制；不等于整个场景真实控件树 |
| `capabilities.json` | 被执行的客户端实际嵌入能力，可回传给没有主程序的制作 AI |

诊断器仅支持有限样本；组件预览中的 phase 是真实 PhaseLabel，其他部分是明确标记的绘制原语样例。它不会证明支付、目标、隐藏信息、联机、鼠标点击或真实大数量附件没有问题。

## 6. 建议的每轮闭环

1. 固定接口、目标内容区大小与人数，保存目标能力文件。
2. 先使用占位素材，跑 validate + 两种尺寸 inspect，处理所有 warnings（允许的警告记录原因）。
3. 先做一枚实际尺寸的 phase / 按钮 / 头像；确认坐标和状态后才批量绘制 12 个阶段。
4. 一次只改一个类别，保存新 ZIP 和 JSON 报告；可选运行 Java 参考诊断。
5. 按验收清单进行真实对局，结果单独写“人工通过 / 未测”，不把静态检查或合成图称为实测。

没有 Java 或主程序时不阻塞纯数据制作：交付原稿、ZIP、inspect 报告、警告解释与待验收项即可。不要承诺一个任意皮肤仅凭资料包就能获得完整游戏级验证。
