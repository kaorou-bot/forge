# 地图冒险的简体中文像素字体（试用）

2026-10-25：仅地图行走、NPC 对话的 Adventure 界面启用；不改普通对局、卡牌字体、竖屏冒险之旅（Quest）或其他语言。尚未发布。

## 字体与实现

- 基于 TakWolf 的 Fusion Pixel 12px proportional zh_hans，版本 2026.10.25：https://github.com/TakWolf/fusion-pixel-font/releases/tag/2026.10.25 。中文等宽、拉丁字母比例宽度，使用简体字形。
- 派生字体名 Forge Adventure Pixel。缺少的当前中文字符由项目现有 SourceHanSansCN.ttf 在构建时补字；保留旧字体所支持且思源字库也支持的字符。不是完整 Unicode 字库，未知字符显示替代符。
- 预生成 BMFont 和两张 2048×2048 PNG，最近邻像素采样；原生字号 12、行高 16，避免挤压汉字。共 36,471 个字形记录，当前译表/冒险中文文本的 3,880 个汉字全覆盖。
- 两页 RGBA 纹理估算 32 MiB（原 LanaPixel 图集约 23 MiB）。不同时加载旧字体，不使用手机运行时 FreeType 生成大量字形，不更改字体全局缓存。
- `AdventureSkinLoader` 只在 zh-CN、皮肤引用 `LanaPixel.fnt` 且旁边有新资源时替换文件名。其他语言、自带字体的第三方世界不改。缺少或加载失败则走原 LanaPixel；旧字体的缺字限制仍存在。
- 控件仍用同一个皮肤注册的 BitmapFont，Textra 对话、按钮、输入框、列表沿用现有接线；没有复制 ui_skin.json。Assets 生命周期保持原样。

## 资源、授权与重建

生成物在 `forge-gui/res/adventure/common/skin/ForgeAdventurePixel*`。同目录 `pixel-font-licenses/` 保存 Fusion 的 OFL、组件授权、思源字体真实版权元数据及 OFL、生成清单及输入 SHA-256。派生字体按 SIL OFL 1.1 发布，不以 Source 保留名命名；原 LanaPixel 未修改。

重建需要 Python、Pillow、fonttools。从上述固定版本下载并解压 `fusion-pixel-font-12px-proportional-bdf-v2026.10.25.zip`，然后在开发工作树根目录运行：

```powershell
python deploy/build-adventure-pixel-font.py <解压目录>
```

生成脚本只离线读取 BDF、现有思源字库、译文和旧字体字形编号；不联网。新增译文后重建可补入缺少的汉字。`build-info.json` 中的 SHA 用于追溯字体输入，不影响独立中文卡名更新来源。

桌面 LibGDX 冒险读取 `res/adventure/common/skin/`；Android 使用解压到资源目录的相同相对路径。普通桌面 Swing 界面不使用这个字体。需要发布包含这些文件的共用资源包，以及含新加载器的移动/LibGDX 程序，单独替换 APK 或单独更新旧程序资源都不会强制切换字体。未更改版本号、正式安装包或服务器。

`dist/font-work/adventure-font-comparison.png` 是 3 倍最近邻显示的字形对照，不是游戏截图。保留运行时原字号，不将小字缩小或拉伸塞进旧尺寸。真实对话换行、按钮高度和用户观感仍需人工验收。

## 验证范围

独立任务 Round34-r1：8 项自动化测试全通过，移动及共用模块编译、Checkstyle 通过。实际 GDX/Skin/Textra 解析、控件共用字体引用、两页索引/边界、3,880 个当前汉字的有效像素、输入哈希/许可副本及重新生成的逐字节一致性均通过。

初轮发现缺第二张 PNG 时第一页纹理遗留，已修复并保留失败证据。加载器现在先检查所有页面存在，再显式管理纹理；缺页时不分配新纹理，后续页解码失败时释放之前创建的页，正常状态由 Skin 负责释放。复测同时覆盖第二页缺失、第二页存在但损坏和正常释放恰好一次。

报告：`dist/independent-network-tests/round34-adventure-font/r1/final-report.txt`。GPU 上传边界由 mock 替代，图片解码/布局数据/加载器为实际实现；不等同真实设备或视觉验收。按用户约定未执行实机或模拟器测试，未打包发布；真实对话/按钮显示、Android 显存压力以及非默认 flip 等参数的视觉效果未覆盖。
