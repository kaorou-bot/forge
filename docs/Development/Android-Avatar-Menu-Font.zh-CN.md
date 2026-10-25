# Android 头像菜单字形纹理异常

2026-10-26，cn1025 后用户报告：长按对局头像的“完全操控”菜单出现空白和红色纹理块，普通“游戏”菜单随后也有相同现象。截图不是通常的文本编码乱码；不能仅凭截图断言所有 GPU 原因均已排除。

## 已确认的代码风险与修复

- VAvatar.longPress 通过 ThreadUtil.invokeInGameThread 调用 MatchController.showFullControl。旧实现直接在该工作线程构造和显示菜单。
- FMenuItem 构造会调用 FSkinFont.getBounds。中文字体使用 FreeType 增量字形；实际 libGDX 1.14.2 的 GlyphLayout/getGlyphs 调用可以生成缺失字形、更新图集并触发 GL 纹理上传。文字测量不一定是纯 CPU 操作。
- 同字号菜单共用字体，因此头像菜单破坏字形纹理与随后普通菜单异常具有一致的风险路径；本轮没有在 GPU 上复现红色纹理，不能将此当成已完成设备视觉验收。
- 修复在工作线程取得开关状态快照，将菜单创建、文字测量、布局和 show 整体放入 FThreads 的渲染队列。选项回调通过现有 ThreadUtil 工作调度修改实际开关，不能把该 cached pool 描述为单一串行游戏线程。
- 展示和点击执行前检查原 GameView、对局未结束及当前 MatchScreen；离开界面、换局或结束游戏后丢弃过期请求。afterGameEnd 保留旧 view/gameView，单纯判非空不足以防止旧菜单弹到大厅。
- 冒险的 ForgeScene.enter 调用 Forge.openScreen(getScreen())，DuelScene.getScreen 返回 MatchController.getView；有效冒险对局适用同一当前界面检查。

不改翻译文件、字体资源、皮肤、对局规则、存档及网络协议。原六个开关的标签、状态和映射保留。现存进程里已经损坏的纹理不能靠替换译表修复；应用修复版后需重新启动进程，无需删除用户存档。

## 独立验证

开发任务 019ff001-903b-7223-afe5-c82577db0508；独立测试任务 01a12110-42fb-7a42-97a8-a074bf5df870。基线 34989fb63803e5861ef1d63cdffdbd6e2e86816b，本轮产品修改 MatchController.java，新增 AvatarFullControlMenuTest.java。

初测 43 例中 6 例发现离开界面/游戏结束后仍执行菜单或点击；补足保护后 r1 共 45 例通过，零失败、零错误、零跳过，编译、Checkstyle 和 diff --check 通过。覆盖 UI 队列构造、工作队列开关、空状态快照、双向切换、七条真实菜单/六个开关映射、换局/退出/结束/替换界面回调，以及字体生命周期、字体大小选择、像素字体、术语和动画相关回归。

固定开发树证据：dist/independent-network-tests/round36-avatar-font/r1/final-report.txt、verification.log、TEST-TestSuite.xml、test-summary.json；初测和本机 libGDX 字节码证据保留在上级目录。

本轮未执行实机、模拟器、GPU 视觉或全部玩法测试；没有生成新 APK、提交、推送或发布。cn1025 正式安装包仍是修复前版本，不能用旧包进行本轮验收。

后续用户于 2026-10-26 授权打包发布，进入 cn1026 发布流程；上段描述 Round36 修复验证结束时的状态。最终制品、再验证和上线记录见 Release-cn1026.zh-CN.md。
