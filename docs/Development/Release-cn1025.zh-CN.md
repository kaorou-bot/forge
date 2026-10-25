# cn1025 翻译修订与发布记录

2026-10-25，用户授权修复语境误译、打包发布。版本 2.0.15-cn1025，显示 2.0.15-汉化-10.25，Android versionCode 2026102501。

## 范围

本轮修订 35 个现有中文语言键，不重命名键，不改变游戏模式、颜色生成、奖励和存档格式。AdventurePlayer.isEmptyDeck 同时识别历史中文默认名“空甲板”，仅在套牌确实为空时作为可用栏位；不重命名旧存档或自定义套牌，非空套牌即使使用该名称也不视为空槽：

- Pile → 随机牌堆，Custom → 自定义，Chaos 菜单与说明统一为混沌。
- Slot 在 SaveLoadScene 是存档栏位；Empty Deck 是空套牌；DeckSelectScene 的 slots 是套牌栏位。
- Zoom 的实际按钮调用 GameHUD.openMap 切换 MapViewScene，按功能译为“查看地图”，不是直译成缩放；丢弃、更新日志、徽记、效果和胜负比按界面语境修正。
- GameHUD 的退出确认实际调用 exitDungeonCallback，改为离开当前区域并返回世界地图，避免原文方向相反。
- Sealed pool 是现开牌池；Uncommon 与 Rare 分别为非普通与稀有。Pile 难度说明明确是起始套牌，不暗示所有后续奖励都减少。
- Jumpstart 统一保留英文产品名并解释为主题包，避免快速入门/快速启动的字面误译；Allied/Opposing colors 使用友好色/对色。

同时交付此前本任务尚未发布的 Round29/32 共 64 张传奇之境地图译文、Round31 下载中断恢复、Round33 Android 12 Stream API 兼容修复及 Round34 冒险中文像素字体。相关实现与限制见各专项文档。历史“未发布”记录描述当时状态，正式结果以本版发布记录为准。

独立卡牌译表仅在构建时核对。已 fetch origin/zh-cn-card-translations，提交 7dc8cf1fcf37a68b7a2d7568ab1dfe317fbe48cb；本地 cardnames-zh-CN.txt 与该来源逐 Git 内容相同，无需替换，不新增客户端译表下载。

## 验证与交付

复用独立测试任务 01a12110-42fb-7a42-97a8-a074bf5df870，开发任务 019ff001-903b-7223-afe5-c82577db0508。本轮对冻结源码重新执行相关编译、自动测试、占位符/译文语境和字体覆盖检查，正式制品另核对 APK 签名/版本/DEX、桌面启动器/JRE、资源 ZIP 目录顺序和实际内容。日志放固定开发树 dist/independent-network-tests/round35-cn1025。

不执行实机或模拟器玩法测试；Android 12 真实设备性能、全部玩法、剧情和像素字体视觉布局仍未覆盖，不能据自动测试结果宣称全部兼容。

正式安装包归档主工作区 dist/releases/cn1025，共用资源 dist/releases/shared/assets-cn1025.zip；不制作增量包。上传不可变对象后从公开 CDN 完整回读校验，推送已核对源码，再比较原清单防止并发覆盖，最后切换清单。保留未知清单字段，不修改/重启大厅中继，不改卡图、tokens 或皮肤资料包。

## 独立验证结果

Round35-r1：104 项回归通过（移动 33、桌面 71），零失败、零跳过，编译与 Checkstyle 通过。新增术语测试的临时目录回退修正后，在有/无专用输出目录参数时各补跑 3 项均通过；这是重复执行，不计为新增 6 种场景。常规字体所需 4,048 码点无缺字，像素字体所需 3,880 汉字无缺字或空白字形。

APK 同签名、递增版本、ZIP 对齐及全部 DEX 的 Stream.toList 门禁通过，实际字节码包含新字体加载器和旧空套牌兼容分支。Windows 55,886 个文件 CRC/目录逐项核对通过，启动器对应 cn1025 JAR，内置运行时为 JRE-only。资源包 22,348 条目/770 个显式目录顺序合格，21,571 个源资源及内层 34,091 个卡牌脚本与当前源码一致。完整安装器编译成功；未执行安装器或设备测试。

最终报告：固定开发树 dist/independent-network-tests/round35-cn1025/final-report.txt，详细证据与制品哈希在其 r1 子目录。旧空槽修复前候选 APK 的 f4e1a6b1... 哈希已作废，不能因同为 cn1025 就发布旧候选。

| 制品 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Android APK | 21438501 | 6c61fcd643d40a39c84d3054e2b3e7826a810ae433461dd97bd583414f012d6c |
| Windows ZIP | 310340625 | 6a5001f7e9d2d71ececc5d6c4e79313629a91923821be443af0fb494dce35bec |
| Windows Setup | 262825084 | a509e5f98b6dfb3ac4f1b4f276b51b33f3311efc2596e94e5904f15e0d1ab917 |
| 共用资源 | 193699826 | fc252699b3c5c48e25d3a358991b3a4c3d568452f9186250a144791d466714e1 |

本版配套资源身份为 2.0.15-cn.assets.20261025r2，与此前仅修复解压目录结构的 cn1023 资源 r1 区分。正式上线结果由主工作区 dist/releases/cn1025/publication-records 中的清单备份和发布记录确认，本文不将本地构建完成等同于服务器发布完成。
