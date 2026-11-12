# 2026-11-12 上游修复同步与资源检查

## 范围

- 上游：`Card-Forge/forge master`，从 `b1f615da3c848a9196be59fdeddceba4bc0eaa94` 同步到 `e17b1f506a128d307f12a039109c3bdef64b8ac7`，共 40 个提交。
- 合并起点：中文发布分支 `9f9495497133aa1b4c566d0ce030f676a443ccf0`。
- 固定同步树：`C:/Users/Administrator/Documents/ChatGPT/mtg forge 汉化-upstream-sync-20261008`，分支 `codex/upstream-sync-20261008`。
- 本轮仅同步、验证、提交推送及检查资源；保留 `2.0.15-cn1110` 和 Android versionCode `2026111001`，不生成或上传新发布物，不切换线上清单，不部署或重启大厅/中继。
- 原主工作区及固定开发树中的独立 PC 冒险客户端、存档编辑器等改动未纳入本次同步，未清理或覆盖。

## 合并与兼容处理

处理 12 个冲突文件，保留中文输入、社区更新、中央大厅/直连、操作时钟、决策中止和皮肤定制；继续排除 iOS/RoboVM。

- 接入上游卡牌规则、优先权、重连状态和新游戏 Tracker 生命周期等修复。
- `MagicStack` 跟随上游改为以 `curResolving` 表示结算状态，并使用统一堆栈；清理自动合并遗留的旧 `setResolving`、`frozenStack` 引用。保留本地异常恢复、离场清理及临时控制权恢复，更新对应测试夹具。
- `Combat` 保留决策帧/中止边界，合入可空阻挡状态检查，避免自动拆箱空指针。
- Tracker 测试注册实际活动连接，增加旧连接消息拒收检查，不放宽产品代码的活动连接校验。
- 保留六张黑市地图的本地化绑定，接入上游玩法和金额修正；旧边框世界任务 26 的九条译文随剧情变动改为撒利德领主/撒利德林地及树林相关内容。
- 补齐新增界面文本和此前遗漏的 13 个中文键，保留 Replay、备份、组队指挥官伤害、世界/环境效果等提示的中文及参数格式。

## 资源包结论：需要随新客户端重建

相对 cn1110，共有 **123 个资源路径变化**：98 个卡牌脚本、3 个系列文件、9 个冒险资源、10 个语言文件、1 个联网套牌列表和 2 个教程路径。

- TRK、TRC、SDS 系列清单本轮未变化；星际迷航的部分卡牌脚本有修正。
- 三个系列文件为 Media and Collaboration Promos、Secret Lair Countdown、Secret Lair Drop。
- **不能只更新旧客户端使用的资源包。** 上游 Station 从带数量的关键字模型改为专门实现，卡牌脚本也从 `K:Station:<数量>` 改为 `K:Station`，新脚本必须与本轮引擎配套。
- 下次正式发布时，桌面安装器/便携包内的 `res`、Android APK 与独立资源 ZIP 必须来自同一个新版本。资源 ZIP 保持既有 `res/` 目录、`res/cardsfolder/cardsfolder.zip`、系列/冒险/语言资源及匹配的 `res/build.txt`。
- 使用新版本专用资源地址，不覆盖 cn1110 不变地址；完整文件 SHA-256 和长度通过校验、源码推送成功并核对线上清单基线后，再最后切换更新入口。
- 本轮不涉及卡图、tokens 图片上传或在线中继服务更新。

### 独立中文卡名表

继续使用 `kaorou-bot/forge` 的 `zh-cn-card-translations` 分支固定来源，不添加客户端自动下载。

本轮核对来源提交 `2a022d734c58463c13d73118b98dcf67b68c9abb`、文件 blob `96f7851af4e4327fc54ddefb5ab0096650fc195e`，工作树已有文件逐字节一致，无需替换。共 38,625 行，SHA-256 为 `5a70d8365e6efcd602da1994f3f8d2bf125e788ce23389ed1e0ea0ba02e73c11`。该哈希仅为本次审计记录，不作为后续更新的固定限制。

## 独立验证

- 开发任务：`019ff001-903b-7223-afe5-c82577db0508`；独立测试任务：`01a12110-42fb-7a42-97a8-a074bf5df870`。
- 测试候选索引树：`0807f6c4ea06bd9ad3f73effe3fa6f1fd3d8018b`。全部测试期间源码冻结；测试释放工作树后仅新增本说明文档。
- 日志目录：固定同步树下 `dist/build-logs/run-d44cc864ca7549778da107abe059e2a8/tester/r5/`。最终报告为 `final-report-r5.txt`，完整命令见各 `*-command.json`，测试结果见 XML 和 `test-results.json`。

| 检查 | 结果 |
| --- | --- |
| 移动端回归（含 99 项屏幕比例数学场景） | 248/248 |
| 桌面、规则、决策、汉化回归 | 236/236 |
| Tracker 生命周期/旧连接拒收 | 4/4 |
| 操作计时（含真实中继重连和超时判负） | 31/31 |
| 网络/进程内协议（含 2、3、4、8 人中继） | 8/8 |
| 总计 | 527/527，零失败、错误或跳过 |
| 桌面/移动/中继编译与 Checkstyle | 通过 |
| Android `-Pandroid-debug compile` 与 Checkstyle | 通过 |

附加审计：全部英文 properties 键均有对应中文键；新键占位符一致；像素字体 3,892 个必需汉字存在且非空白；内置 TTF 按既有字符归一化规则覆盖 4,061 个必需字符；9 个 Android 反应堆模块的 3,596 个当前产品 class 无 `Stream.toList` 引用；3,019 个受版本控制的 Java/构建文件的平台排除检查通过。

Windows 本机网络测试继续通过临时进程级 `-Djdk.net.unixdomain.tmpdir=C:/Users/Administrator/Documents/ForgeNio` 解决 AppData 路径下 Selector 初始化问题；正反对照和真实网络场景均有日志，不修改全局配置或发布包。

**未覆盖：** GUI 交互、模拟器、真实设备、全部玩法、长期压力以及最终 APK/安装器/DEX。编译、字体和 class 检查不等于真实设备或第三方依赖全部兼容。

## 已知非阻塞事项

`Realm of Legends/maps/map/barbariancamp/Red_Black_Market.tmx` 中一个英文分支仍有上游既存 `Spend 100,0000 gold` 笔误；中文绑定及实际金额为 100,000，本轮另一分支已随上游修正。保留在审计记录中，不将既存英文文案问题混同为本轮新增玩法错误。
