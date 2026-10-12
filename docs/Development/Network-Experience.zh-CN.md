# 联机体验优化与验收（历史开发记录）

本轮改进已汇总进入 cn1012 正式包。当前实现、正式包验证和已知限制见 [大厅体验扩展](Lobby-Features.zh-CN.md)。以下保留 cn1009nettest1 阶段的实现及验收记录，“测试版/未发布/尚未实现”等措辞仅对应当时阶段，不代表 cn1012 当前状态。

本次是 Android / Windows 客户端测试版，未推送、未切换线上更新清单，也不需要更新或重启现有大厅服务。仍由房主执行权威规则结算；不改为双端独立计算。

## 实现范围

| 位置 | 行为 |
| --- | --- |
| 两端建房、加入、读取房间列表 | 共用 `NetworkOperation` / `ConnectionAttempt` 生命周期，显示连接、加密握手、读取列表、等待房主接通、连接对局等阶段。取消会关闭已登记的连接资源；当前操作清理完之前不启动第二个操作。 |
| 大厅 TCP/TLS | 连接超时 10 秒，控制消息及 TLS 握手读取超时 35 秒；首次 Forge 房间确认最多等待 20 秒。保留证书及主机名校验。 |
| 中继客机重连 | 每次打开新的 RelayGuestProxy 和本地端口，保留原服务器、房间号和密码。房间界面收到 LobbyUpdate 即恢复；对局中等 setGameView 在界面线程应用后再解除重连状态。 |
| Android 回到前台 | 已登录客机主动发送心跳；8 秒无任何新消息则关闭旧通道并进入已有重连流程。不是对局选择倒计时。 |
| 房主端通道 | 单个客机建通道失败不再关闭整个房间，结束的桥接从集合移除；房主控制连接失效仍按既有逻辑关闭房间。 |
| 房间清理 | 停止期间不登记新的重连记录；事件循环关闭后再次排空记录；关闭监听器带代次检查，防止旧房间的回调停止新房间。空闲时不预建 Netty 线程组。 |
| 自动让过 | “取消本次让过”通过已有 YieldUpdate 同时取消标记、当前堆叠临时让过和本回合让过，支持远端控制器。长期规则与阶段停留设置不变。 |
| 房间分享 | 在线菜单提供复制房间号、输入房间号加入；列表显示房间号，满房提前提示。必须选择同一服务器，仍验证房间密码。 |
| 诊断 / 开销 | 可复制版本、角色、连接状态、往返心跳延迟、距上次消息时间及回合/阶段。报告不包含地址、密码、玩家名和套牌。逐消息日志改为 debug；已完成查询释放，过期回复忽略；客机信息查询上限 5 秒。 |

取消和异常恢复只清理本次操作创建的资源，不清用户缓存、卡图、译表或存档。网络线程负责阻塞 I/O，UI 线程负责视图更改。不能把 UI 超时直接当成玩家选择的答案。

## 兼容性与明确限制

- 线协议与 `forge-cn-net-1` 标识不变。本次修改的 relay-client 是客户端依赖，不是线上 forge-lobby-relay 服务；无需安装新服务端包。双方均使用测试包可覆盖全部改进。
- 不改变现有 5 次重连退避和房主保留座位规则。不保证后台被系统杀死、房主退出或房主断网后能恢复；未实现房主迁移。
- **尚未实现所有弹窗选择的断线恢复**。任意规则弹窗需要请求去重、旧弹窗撤销与重放的完整生命周期，本次没有引入不完整的重放机制。自动重连测试不等同于所有牌和所有结算场景恢复成功。
- 没有新增规则计算分摊、预测施放或乱序发送，不会把断线期间点击的旧操作在重连后补发。也没有声称降低所有设备的结算耗时；本次性能改进是有界查询、线程与资源清理、日志级别。
- 维持现有自动让过策略与“等待某玩家”的提示，不另加一套重复规则。复杂多人连续局、真实 Android 网络切换仍需要人工验收。

## 自动化验证记录

1. `network-tests-final.log`：6 项 relay-client、10 项 desktop 专项测试，零失败。含真实 TCP 及中继协议、2/3/4/8 人小牌库对局、2–8 座位范围、连续两次中继重连、查询超时/迟到回复及远端取消让过。
2. `network-tests-resume.log`：6 项 relay-client、20 项 mobile、6 项 desktop 测试，零失败。增加对静默丢失入站消息的前台心跳探测验证；保留 Android 动画、字库、语言与退出生命周期回归。
3. 两端编译及 Checkstyle 通过，中文字体 3,927 码点无缺字，平台排除守卫 7 项通过。
4. 最终收尾 `network-tests-delivery.log`：6 项 relay-client、7 项 desktop 专项测试再次通过（含新加的平台版本和诊断隐私测试），同时通过 mobile 编译及 Checkstyle。与前两轮有重复测试，不累加为独立覆盖数量。

以上日志保存在固定开发工作树忽略目录 `dist/`。Windows 本机测试 JVM 需临时设置 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\nonexistent-forge-selector-test`，测试结束恢复原值；该参数不写入产品。自动网络对局使用简单基本地测试套牌，不等同于完整卡牌规则或真实手机长时间测试。

复查专项可用 Maven `-pl forge-gui-desktop,forge-gui-mobile -am -Dcheckstyle.skip=false -Dsurefire.failIfNoSpecifiedTests=false -Djava.awt.headless=true test`，通过 `-Dtest` 选择 `ConnectionAttemptTest,RelayClientIntegrationTest,ReplyPoolTest,RemoteYieldCancelTest,RelayReconnectTest,NetworkConnectionSettingsTest`；多人对局用 `NetworkPlayIntegrationTest` 的 `testTrueNetworkTraffic`、`testTrueNetworkTrafficThroughRelay`、`testMultiplayerTrafficThroughRelay` 和 `testNetworkLobbyCreatesEveryAdvertisedSeat`。

## 人工验收清单

- 桌面、Android 各创建一次服务器房间；取消读取、取消建立连接后可重新操作，不能留有假房间或失效的已连接 UI。
- 复制房间号，在另一端选同一服务器后输入；检查有密码、密码错误、满房和不存在的房间。
- 普通对局短暂断网再恢复；Android Home 后返回；诊断显示恢复连接。取消重连后应能返回并重新建房。
- 尝试本回合/堆叠让过，再“取消本次让过”；长期自动让过规则保持原设置。
- 同房间继续下一局，以及退出、再建房；分别测试手机作房主与客机，关注既往右上角菜单/离开房间问题。
- 将“规则选择弹窗中断网”“房主离线”“Android 被杀进程”视为单独的边界场景，不承诺本版无损恢复。

## 打包与发布边界

版本标识 `2.0.15-cn1009nettest1`，显示 `2.0.15-汉化-10.09-联机测试1`，Android versionCode `2026100901`。使用既有自动打包脚本和原签名，不产生增量包。测试 APK 和内置 JRE 的 Windows 包集中存放主工作区 `dist/releases/cn1009nettest1/`。Windows Launch4j 要求 Maven 版本只用一个后缀连字符，因此测试标识不用 `cn1009-nettest1`。

先交付测试包；用户验收并明确要求发布后，才提交/推送和切换更新清单。正式发布继续独立核对中文译表来源；不把客户端译表自动下载重新加回来。任何后续线上大厅服务变更都要提前通知用户。

### 本机界面验证

使用仅绑定 `127.0.0.1` 的临时中继和 Android 模拟器的 `adb reverse` 验证，没有在公共服务器建房。桌面成功创建房间，Android 输入房间号找到并加入；Home 切后台后返回，仍保持连接，诊断可见 `CONNECTED`、`relay` 和心跳往返值。Android 主动断开可返回单机页面。

界面验证补齐了桌面下拉菜单与 Android 横屏常驻侧栏的连接状态刷新。诊断版本从平台接口取得，避免 Android Java Package 元数据的 `0.0` 占位版本。

最终 APK 再次覆盖安装验证：Android 创建本机中继房间成功，横屏侧栏立即出现复制房间号及诊断入口；诊断显示 `2.0.15-cn1009nettest1`。主动离开后，中继健康接口恢复为 0 房间、0 连接。测试完停止本机中继与本次启动的模拟器、撤销 adb reverse，并恢复模拟器原有偏好；测试服务器配置另存于忽略目录的日志文件，不带入安装包。

测试包未上线：启动时如果社区更新器提示安装线上 `cn1008r3`，请选择“稍后更新”，不要把测试客户端覆盖回正式旧版。本次没有修改正式更新规则。

### 最终测试交付文件

- Android APK：SHA-256 `0c514d45a359fab28aa5cab58d05041ab08a9bab96e96cbe8c9098d2aadd1270`。
- Windows ZIP（内置 JRE）：SHA-256 `f997ffe43f12f2bd4d0b125755e5e2b276205f170a4b75674769304e018762f2`，7-Zip 完整性检查通过，55,746 个文件。
- 本轮先提供以上完整测试包。额外的 Setup 压缩在进行中主动停止，未完成 EXE 已移除；需要正式安装器时可用既有 `build-desktop-installer.ps1` 从已验证的 Windows 解包目录重新生成。没有发布或切换线上清单。
