# PC 对战界面扩展接口（版本 1）

本接口只用于 Swing PC 客户端。移动端保持原有界面。游戏规则、AI、卡牌选择、区域可见性和目标连线继续由现有控制器处理。

## 使用入口

进入对战后，打开 **布局 → 对战界面**：

- **经典布局**：默认选项，沿用已有 XML 布局和多人排列设置。
- **竞技场布局（示范）**：战场位于左侧主区域，堆叠和卡牌预览位于右侧，手牌和操作提示位于下方；玩家信息移到战场右边。
- **使用皮肤布局**：从当前皮肤目录读取 UTF-8 `match-ui.json`。没有此文件时使用经典布局。
- **重新加载界面配置**：修改 JSON 后重新读取，无需重启。切换图片皮肤时，若选择了“使用皮肤布局”，也会重新加载。

选择记录在用户 preferences 目录的 `desktop-match-ui.properties`。经典布局继续使用 `match.xml`；新布局的拖拽结果保存在独立的 `match-ui-<摘要>.xml`。摘要区分界面提供者、皮肤目录、布局定义和实际玩家/手牌组合。修改区域定义会产生新的保存位置；仅修改玩家面板内部位置会即时应用。

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

这一版建立的是布局与组件组合接口：可以大幅重新组织矩形区域和玩家内部结构，仍复用 Forge 的拖拽单元格、卡牌排列和绘制。JSON 暂不支持扇形手牌、跨玩家面板的任意覆盖、全新卡牌渲染、动画脚本或交互规则；这些需要后续扩展对应组件接口，而不是在 JSON 中添加坐标即可完成。

自动测试 `MatchUiLayoutTest` 覆盖 2～8 名玩家、0～全部手牌、开发模式、稀疏手牌编号、布局覆盖与重复检查、操作栏独立性、错误配置以及 Swing 组件重排/缩放/监听器保留。

本次在独立测试用户目录中实际验证了双人对战：竞技场与经典布局往返切换、800×600 窗口与最大化窗口、发牌、保留起手牌、从手牌使用沼泽，以及卡牌预览和日志更新。测试启动使用项目配置的 Java 模块开放参数；本机 JDK 的 Windows Unix-domain socket 回环连接失败，测试进程通过临时 `jdk.net.unixdomain.tmpdir` 设置触发 JDK 的 TCP 回退，未修改产品启动配置。

运行：

```text
mvn -pl forge-gui-desktop -am test -Dtest=MatchUiLayoutTest -Dsurefire.failIfNoSpecifiedTests=false
```

发布前还应实际验收：多人战场的卡牌密集显示、攻击/阻挡/目标连线、网络对战、区域标签、控制权变化，以及高 DPI 和小窗口下的可读性。通过布局单元测试不等于这些交互已经全部验收。
