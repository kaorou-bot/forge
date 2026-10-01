# Forge 独立皮肤制作资料包

2.0.1 提供暮辉星台示例、响应式/多人布局、锚点、个人设置、浮窗记忆、几何编辑、头像轮廓与文字排版，并修正紧凑战场、详情区域和日志/堆叠互斥显示。**先读 `docs/v5扩展规范.md` 和 `docs/高效制作与排错.md`，不要先编译或修改 JAR。** 同时保留 v3/v4 示例与原有尺寸诊断、能力对照、安全迁移和可选 Java 诊断。客户端兼容性看能力清单，不只看 cn 版号。

这是供人类程序员与 AI 使用的离线制作资料，不是 Forge 安装包。无需 Forge 主程序、源码、Java、Maven 或互联网，就能阅读说明、修改示例、校验配置和生成皮肤 ZIP。

只阅读和编辑文件不需要额外软件环境。运行附带工具需要 **Python 3.10 或更高版本**，只使用标准库，不用 pip 安装依赖。工具不会连接网络、安装软件或更改 Forge 用户设置。

## 从这里开始

1. 解压整个资料包到自己的工作文件夹。
2. 先读 `docs/制作入门.md`；程序员和 AI 再读 `docs/AI制作规范.md` 与 `docs/独立工具说明.md`。
3. **复制** `examples/dusk-sanctum/` 为 `work/my-skin/`，修改副本。示例带背景、中文字体及字体许可，不用另找素材。
4. 打开副本中的 `match-ui.json`，更改 `id`，先换背景和颜色，再调整布局。
5. 在资料包根目录运行下面的命令。Windows 可以把 `python` 换成 `py -3`；macOS/Linux 常用 `python3`。

```text
python tools/skin_tool.py validate work/my-skin
python tools/skin_tool.py inspect work/my-skin --width 1920 --height 1028 --out output/my-skin-measurements.json --json
python tools/skin_tool.py preview work/my-skin --out output/my-skin-layout.html
python tools/skin_tool.py pack work/my-skin --out output/my-skin.zip
python tools/skin_tool.py validate output/my-skin.zip
```

校验只读取输入；预览与打包只写你指定的新输出文件。已有输出不会被覆盖，请换个文件名。输出不能放在皮肤原稿目录内，避免把旧输出递归打包进去。

首次试用也可以直接把上述 `work/my-skin` 换为 `examples/dusk-sanctum`。生成的 HTML 用浏览器打开，无需启动服务；它只是占位矩形和背景示意，不能模拟实际卡牌、文字排版、操作或自动避让。

## 包里有什么

| 目录或文件 | 用途 |
| --- | --- |
| `docs/制作入门.md` | 面向普通制作者的操作说明 |
| `docs/AI制作规范.md` | 自足的详细接口说明，不要求先获取源码 |
| `docs/独立工具说明.md` | 工具命令、退出码、检查范围和差异 |
| `docs/AI任务模板.txt` | 可直接交给 AI 的任务文本 |
| `docs/验收清单.md` | 静态检查与人工对局验收清单 |
| `examples/dusk-sanctum/` | 推荐 v4 双人美术示例，带背景、图标与中文字体，与客户端内置版本同源 |
| `examples/dusk-observatory/` | v5 功能示例，含紧凑/宽屏及 3～8 人方案，与 cn1002 内置版本同源 |
| `examples/minimal/` | v3 无外部素材的完整双人配置，用于兼容旧客户端及学习结构，不是视觉成品 |
| `examples/v4-workbench/` | v4 图标、形状、透明浮窗和居中排列示例 |
| `ready-to-import/` | 已打包好的皮肤 ZIP，可交给有兼容主程序的人验收 |
| `previews/` | 各示例的离线布局示意 HTML |
| `tools/skin_tool.py` | 离线校验、尺寸诊断、能力对照、迁移、预览、打包及可选 Java 诊断 |
| `tools/skin-capabilities.json` | 与本版客户端同源的参考能力清单，不等于目标旧客户端的能力 |
| `docs/高效制作与排错.md` | 尺寸公式、阶段坐标、控件差异、十轮制作经验与推荐闭环 |
| `tools/test_skin_tool.py` | 工具自身的自动测试 |
| `LICENSE.txt`、`NOTICE.md` | 工具许可与素材许可说明 |
| `manifest.json` | 包版本、接口版本和内含文件 SHA-256，用于完整性核对 |

`examples/dusk-sanctum/fonts/OFL.txt` 必须随该字体保留。背景来源记录在示例 `ASSETS.md`。不要把整个资料包作为皮肤导入：真正导入的是 `ready-to-import/` 或工具输出的皮肤 ZIP。

## 交给 AI 怎样使用

把整个资料包交给有文件读取能力的 AI，告诉它先读 README、AI 制作规范和独立工具说明。没有必要让它下载 Forge 仓库。只有需要新增接口能力、修改主程序时，才需要额外提供主程序源码。

不支持文件工具的聊天模型也能生成配置建议，但不能替你实际运行校验或打包，不应把它的口头“通过”当作测试结果。

## 制作完成不等于对局验收通过

静态检查通过只表示本工具覆盖的规则通过。字体缺字、Java 图片解码、按钮可点击性、支付费用、目标连线、网络对局和高 DPI 显示仍需支持对应 match-ui 格式版本的 Forge 桌面程序验收。接收者不需要源码，只需兼容主程序。

当前接口仅用于桌面对战画面，不适用于 Android 或主菜单。暮辉秘境是双人示例，多人或多副可见手牌会回退；不要把“正常回退”宣传成“具有八人专用皮肤”。

## 包版本

资料包 2.0.1，参考能力 2026-10-02.1，接口依据 cn1002 的 match-ui v3/v4/v5。静态与运行时探针检查不能替代玩家实机验收。后续 Forge 的接口变动可能需要更新工具；客户端实际解析和行为是最终依据。本包不代表 Card-Forge 官方发布。

## v5 示例

新增示例 `ready-to-import/dusk-observatory.zip`（暮辉星台）。请先读 [v5 窗口适配与制作体验](docs/v5扩展规范.md)。包含紧凑/宽屏、3～8 人方案、锚点、个人设置、浮窗持久化、几何编辑、头像轮廓与文字排版。旧 v3/v4 示例仍保留。
