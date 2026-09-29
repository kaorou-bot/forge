# 上游同步：永久排除 iOS

2026-09-29 用户确认：继续每周二 16:00（Asia/Shanghai）同步 Forge 上游主分支，
但本民间汉化分支只维护 Android、桌面及大厅中继，不再引入 iOS 支持。
独立 `zh-cn-card-translations` 的译表更新不受影响。

## 每次同步必须执行

1. 保留汉化分支的已有定制和用户工作区；有脏文件时使用安全的独立工作树。
2. 合并上游时保留 `forge-gui-ios/`、iOS IPA/兼容性审计工作流和
   `docs/Development/iOS-Builds.md` 的删除状态，移除根 POM 的 `ios` profile、模块及依赖。
   对上游新增或改名的 Apple 移动端构建/签名/部署工具也要检查并排除。
3. 共享代码中仅剔除明确的 iOS 专用分支、RoboVM/MobiVM 后端和适配器。
   Android 中文输入、桌面/macOS、图像生命周期、线程安全、编码及资源读取的通用兜底
   不因最初为 iOS 修复而回退；按 Android/桌面的原有执行路径处理。
4. 不得按 `ios` 子串盲目删文件：牌名、卡图、牌表及第三方源码注释中可能出现相同字符。
   不得用 `.gitignore` 或全仓库 `merge=ours` 冒充同步排除，它们不能正确过滤上游已跟踪文件。
5. 合并后运行 `pwsh -File deploy/check-platform-scope.ps1`，并完成受影响的桌面、移动端
   和 Android 编译/测试；守卫失败或共享逻辑无法可靠区分时，停止推送并报告。

## 防止重新引入

`deploy/check-platform-scope.ps1` 检查 Git 跟踪及非忽略的新文件，拒绝 iOS 专用路径、
构建配置和 Java 平台入口。GitHub 的 `Community platform scope` 在推送和 PR 时运行同一检查。
检查是失败守卫，不是自动删除器；不能替代对新命名或跨平台改动的审查。

删除内容仍保留在 Git 历史中。不得为了“清理 iOS”删除用户原始脏工作区、未跟踪缓存、
已发布安装包或线上服务器文件；本次不重新发布客户端，也不重启大厅。
