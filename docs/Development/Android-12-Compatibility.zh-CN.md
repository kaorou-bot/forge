# Android 12 冒险之旅兼容修复

## 反馈与证据

用户设备为三星 Note10+、Android 12，反馈竖屏经典模式的“冒险之旅”（Quest）在结算奖励前及进入卡牌商店时卡死，回退 cn1012 后恢复。不是角色在地图上移动的 Adventure 模式。旧存档 Gisa X.dat 只作为已有线索保留，不改写用户存档。

Round16 独立审计已确认 cn1013 正式 APK 的 QuestUtilCards.generateSinglesInShop、generateQuestBooster、getCompletionPercent 和 QuestWinLoseController.awardBooster 直接引用平台 Stream.toList，无兼容实现。Android 官方文档标明该方法自 API 34 才提供，Android 12 为 API 31。商店空库存生成及结算时刷新库存会走到相关路径；NoSuchMethodError 不是 Exception，原有异常捕获不能保证收尾。旧 APK 的静态检查不是用户设备错误日志，不将所有卡死都归因于这一处，也不将 API 36 上的旧测试当作 API 31 验证。

来源：[Android Stream.toList API](https://developer.android.com/reference/java/util/stream/Stream#toList())。历史审计见固定开发树 dist/independent-network-tests/round16-quest-hang/round16-api31-static-report.txt。

## 本轮修复（2026-10-25，Round33）

基于 f377d6926a2dda16439edf918eefd933f7bad5a9 及当前未发布工作，重新扫描 Android 使用的产品源码。上游已将部分旧调用改为 Collectors.toList，但商店 generateSinglesInShop 仍留有一处；联机房间查找、赛制显示和操作时钟还残留四处。本轮一并替换，不改卡池、赛制过滤、随机奖励、扣时规则或网络协议。

新增 StreamUtil.toImmutableList 收集器，用兼容接口实现顺序稳定、允许 null、不可修改的列表快照，保持原 Stream.toList 的这些契约。不能改用 List.copyOf/Collectors.toUnmodifiableList 后假定旧 Android 都可用，也不能仅设置 --min-api 就认为所有 Java 库方法会自动补齐。

deploy/VerifyAndroidStreamApi.java 在构建工具链 JDK 上运行，逐份检查实际生成的 classes*.dex 方法引用，拒绝包含平台 java.util.stream.Stream.toList 的包。deploy/build-android-community.ps1 在 D8 后、APK 组装和签名前强制执行。守卫检查包含依赖的最终 DEX，不只用源码正则扫描；没有 DEX、格式异常或检查失败都会停止构建。它仅阻止已知 Stream.toList 兼容回归，不代表完整 Android API 扫描、设备测试或 APK 安全审计。

本轮不清除用户数据、不修改存档、卡图和资源下载源，不改版号，不提交、推送或部署。既有 Round29/32 翻译、Round30 资源发布保护和 Round31 下载恢复工作保留。

## 验证范围

独立测试复用任务 01a12110-42fb-7a42-97a8-a074bf5df870。本轮要求兼容收集器契约回归、Quest 商店/奖励路径验证、受影响联机回归、编译和 Checkstyle，以及构建实际 Android 测试包并检查最终 DEX；旧 cn1013 DEX 必须被新守卫拒绝。新包只作验证，不覆盖正式发布目录。

按用户约定，不运行实机或模拟器。因此真实三星 Android 12 上的商店、奖励和界面操作仍需用户验收，不能仅凭编译和字节码审计宣称已复现并彻底解决用户设备上的全部卡顿。

## 独立验证结果

Round33 正式复跑 46 项全部通过：AndroidStreamCompatibilityTest 4 项、获准新增的 QuestAndroidCompatibilityTest 6 项、QuestRewardLocalizationTest 6 项、TimedGameDecisionsTest 27 项、RelayRoomPresentationTest 3 项。移动、桌面及共用模块编译和 Checkstyle 通过。Quest 新测试调用真实筛选和入库逻辑，随机 UnOpenedProduct 边界使用 mock；不将其说成完整商店、showRewards/awardBooster 界面或真实对局验证。

初次测试调用遗漏时钟测试所需隔离目录参数，setup 拒绝运行、27 项跳过；补齐测试目录与进程级 APPDATA/LOCALAPPDATA 后，全部 46 项重新通过。未为此修改产品代码。初次运行的用户 Forge 目录针对性时间检查未发现写入，但不是完整文件系统追踪。失败日志仍保留，不计为产品故障或通过结果。

既有自动脚本成功构建签名测试 APK。旧 Maven Android 插件的 D8 步骤仍按已知情况失败，随后由脚本使用本轮生成的中间产物和短路径 D8 完成，不能称前段 Maven install 本身成功。新门禁在签名前通过；签名 v2/v3、对齐和包内容校验通过。独立 dexdump 完整扫描最终两份 DEX，直接平台 Stream.toList 调用均为零。旧 cn1013 DEX、空目录、截断 DEX，以及只在第二份 DEX 放入危险引用的负例均被正确拒绝。

测试包位于固定开发树 dist/independent-network-tests/round33-android12/apk/Forge-2.0.15-cn1023-Android.apk，大小 21,430,309 字节，SHA-256 为 2bfafd43c297b904f2d31ca40e3bb3aa03d86cc63fa937b9bc295140b9cda3ff。仍沿用 cn1023 / 2026102301 标签，仅为未发布测试构建，与主工作区正式 cn1023 安装包不同；正式包前后哈希不变，不据此自动上线或替换正式包。

98 个冻结文件前后哈希一致，独立任务仅在授权范围新增 Quest 测试源码，无遗留测试 Java 进程。开发已复核正式报告与 APK 哈希，工作树及缓存交还后仅补本文。报告及命令证据为 dist/independent-network-tests/round33-android12/final-report.txt。未安装或启动任何实机、模拟器，未测试存档迁移、完整玩法、反射调用或其他全部 Android API；尚未提交、推送或正式发布。
