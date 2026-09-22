# Forge 简体中文民间版开发与发布手册

本文记录 `kaorou-bot/forge` 的 `zh-cn-community-release` 分支在 Windows 上维护、构建、测试和发布到阿里云的实际经验。目标是让后续维护者能够复现当前版本，并避免已经发生过的更新循环、黑屏、中文缺字、输入法失效和 Windows 启动器失配等问题。

## 最新发布约定（2026-09-22）

用户在 cn0922r4 发布过程中要求：**之后不再制作增量包**。后续桌面发布仅提供完整安装包和内置 JRE 的便携 ZIP。

- 运行 `deploy/build-desktop-installer.ps1` 时不再传入 `PreviousPackageDirectory`、`PreviousVersion`、`PreviousBuildId`，避免旧版全目录哈希比对和增量压缩。
- 后续更新清单只使用新的完整安装包作为 `desktop.url`，移除遗留的全部 `desktop.patch.*` 字段，不能沿用旧增量地址。Android 是否更新由实际改动决定，不因桌面发版而重复发布。
- 下文关于增量包的描述是历史记录，不再作为后续发布的默认流程。已经开始构建的 cn0922r4 按当次流程收尾；不删除已发布的历史文件。

## 1. 项目边界与仓库

- 上游仓库：`https://github.com/Card-Forge/forge.git`
- 民间版仓库：`https://github.com/kaorou-bot/forge.git`
- 长期维护分支：`zh-cn-community-release`
- 用户支持：QQ群 `813597628`
- 更新域名：`https://update.mtg-forge-kaorou.vip/forge/`
- 卡图域名：`https://images.mtg-forge-kaorou.vip/cards/`
- OSS 更新 Bucket：`forge-cn-update-a8k3`
- OSS 卡图 Bucket：`forge-cn-images-a8k3`

本版是完全免费的民间汉化，不代表 Card Forge 或 Wizards of the Coast 官方。发布说明和程序内联系方式必须保持这一表述。

## 2. 当前民间版特性与主要代码位置

| 特性 | 主要位置 |
| --- | --- |
| 默认简体中文 | `forge-gui/src/main/java/forge/localinstance/properties/ForgePreferences.java`、`forge-gui/forge.profile.properties.example` |
| 中文界面与卡名 | `forge-gui/res/languages/zh-CN.properties`、`cardnames-zh-CN.txt` |
| 民间版身份、QQ群与发布说明 | `CommunityEditionInfo.java`、`forge-community-release-notes-zh-CN.txt` |
| 独立更新和卡图线路 | `forge-update.properties`、`ForgeUpdateConfig.java`、`ImageFetcher.java`、`LibGDXImageFetcher.java` |
| 单一更新渠道 | `AutoUpdater.java`、`AssetsDownloader.java` |
| 安卓内置 CJK 字体 | `forge-gui-android/assets/bundled-font/`、`AssetsDownloader.installBundledCjkFont()` |
| 安卓动态 CJK 字形 | `forge-gui-mobile/src/forge/assets/FSkinFont.java` |
| 安卓原生中文输入框 | `FTextField.startNativeAndroidEdit()`、`DefaultAndroidInput.getTextInput()` |
| 禁止上游 Sentry 上报 | `forge-gui/src/main/java/forge/gui/error/BugReporter.java` |
| 阿里云发布 | `deploy/aliyun/*.ps1` |
| 字库覆盖检查 | `deploy/VerifyCjkFontCoverage.java` |

## 3. 开发环境

已经验证的环境：Windows 11、JDK 21、Maven 3.9.12、Android Build Tools 36.0.0、最低 Android SDK 26、7-Zip 和 ossutil 2.3.0。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:ANDROID_HOME = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_PREFS_ROOT = 'C:\Users\Administrator\.android'
$env:MAVEN_OPTS = '-Xmx4g -Dfile.encoding=UTF-8'
```

AccessKey 只应存在于用户目录的 ossutil 配置中；不要写入仓库、文档、脚本参数或终端日志。Android 当前发布包使用本机 `C:\Users\Administrator\.android\debug.keystore`。同一应用要覆盖安装，后续必须继续使用同一签名；准备面向更大范围长期发布前，应迁移到妥善备份的正式密钥，但改变签名会使已有用户无法直接覆盖安装。

## 4. 上游同步方法

不要直接在 `master` 上开发。同步前保证工作区干净并留存当前可发布版本标签。

```powershell
git switch zh-cn-community-release
git fetch upstream
git fetch origin
git merge upstream/master
```

处理冲突时优先检查：

1. `pom.xml` 中的版本属性；
2. `ForgePreferences` 默认语言和更新渠道；
3. `AssetsDownloader` 的镜像更新、字体安装和资源包逻辑；
4. `FTextField` 与 Android 后端输入法代码；
5. `ImageFetcher` 的民间卡图 URL；
6. 上游新增加的 Discord、论坛、Sentry 或下载全部卡图入口；
7. 上游新增英文文案是否需要补充到 `zh-CN.properties`。

合并后至少编译桌面模块和移动模块，并重新执行字库覆盖检查。上游可能改变 libGDX、Android 插件或输入系统，不能只凭“合并无冲突”判断可发布。

## 5. 版本体系：最容易出错的部分

项目同时存在三种版本标识：

| 属性 | 示例 | 用途 |
| --- | --- | --- |
| `snapshotName` / Maven `revision` | `-cn0813r2` / `2.0.15-cn0813r2` | Maven 产物名、Android `versionName` |
| `displayVersion` | `2.0.15-汉化-08.13.2` | 面向用户的显示版本、桌面 JAR Implementation-Version |
| `androidVersionCode` | `2026081303` | Android 覆盖安装所需的单调递增整数 |

修改位置在根目录 `pom.xml`。每次发布必须同时更新三者，其中 `androidVersionCode` 只能增加，不能回退或复用。

### Android 无限更新的根因

Android 的 `AssetsDownloader` 使用已安装 APK 的 `versionName` 比较 `android.version`。APK 当前值是 ASCII 的 `2.0.15-cn0813r2`。若清单写成中文显示版本 `2.0.15-汉化-08.13.2`，两者永不相等，用户安装完仍会反复提示更新。

规则：

- `manifest version` 与 `android.version` 必须等于 APK 的实际 `versionName`；
- `desktop.version` 应等于桌面 JAR 的 `Implementation-Version`，可以使用中文显示版本；
- 发布后必须用 `aapt dump badging` 和公网清单做精确相等校验。

`publish-clients.ps1` 的 `ManifestVersion` 参数表示 Android 的精确 ASCII 版本；如桌面显示版本不同，使用 `-DesktopVersion` 单独传入。

## 6. 中文资源维护

### 6.1 卡名映射

正式文件为 `forge-gui/res/languages/cardnames-zh-CN.txt`。外部映射文件更新时：

1. 保留 UTF-8 编码和 `英文名|中文名|类别|规则` 的四列结构；
2. 规则文本自身可能含竖线，不能无条件拆分所有 `|` 后截断规则；
3. 检查重复英文键、空英文键、异常行数和乱码；
4. 双面牌、特殊画框和同名不同版本不应仅靠中文文件名判断卡图；
5. 构建后确认 APK 内存在 `assets/localization/cardnames-zh-CN.txt`。

### 6.2 properties 文件

中文 properties 文件必须保持 UTF-8。占位符数量必须与英文项一致，例如 `{0}`、`{1}` 不可丢失或调换。添加新键时同时修改 `en-US.properties` 和 `zh-CN.properties`，否则回退语言或本地化检查可能失败。

### 6.3 CJK 字体

字体和许可证位于 `forge-gui-android/assets/bundled-font/`。不要根据当前文案预生成有限字符集的位图字体。卡名映射和用户输入会持续增加字符，有限字库必然再次出现方框。当前方案用 FreeType 增量生成字形，并为新方案设置缓存版本；修改字体或生成算法时必须让旧缓存失效。

发布前运行：

```powershell
& "$env:JAVA_HOME\bin\javac.exe" deploy\VerifyCjkFontCoverage.java
& "$env:JAVA_HOME\bin\java.exe" -cp deploy VerifyCjkFontCoverage
```

审计应覆盖中文界面、卡名、发布说明和必要配置文本。补充平面字符无法由 libGDX 的 UTF-16 `char` 字体接口可靠呈现，审计工具会把这类字符一并报告。

### 6.4 首次启动资源

Android 在完整资源包下载和解压之前就要显示启动界面，因此 APK 必须自带：

- `fallback_skin` 的启动背景和字体；
- libGDX `lsans-15.fnt/png`；
- `libgdx-freetype.so` 等四种 ABI 原生库；
- CJK TTF 和许可证；
- tinylog/SLF4J 的 `META-INF/services`。

缺少这些内容可能表现为授权后黑屏、首启崩溃或中文回退为方框，而不是明显的“文件不存在”提示。

## 7. Android 中文输入经验

libGDX 的 GL Surface 文本框不能稳定接收 Android IME 的拼音组合与候选词提交。只处理 `keyTyped`、`commitText` 或单个搜索框都不足以覆盖不同输入法和所有界面。

当前策略：Android 上所有 `FTextField` 单行输入统一调用系统原生 `EditText` 对话框；完成后一次性把字符串提交回 Forge。桌面和其他平台保留原输入路径。

修改时必须人工覆盖卡牌搜索、牌组名称、数字输入、取消恢复、确认回调和再次打开时的选择状态。仅在模拟器看到键盘弹出不等于中文输入成功；必须用实体设备和主流中文输入法选词后确认文本真正进入 Forge。

## 8. 构建流程

### 8.1 快速编译验证

```powershell
.\.tools\apache-maven-3.9.12\bin\mvn.cmd `
  '-Dmaven.repo.local=C:\Users\Administrator\.m2\repository' `
  -DskipTests -pl forge-gui-mobile -am package

.\.tools\apache-maven-3.9.12\bin\mvn.cmd `
  '-Dmaven.repo.local=C:\Users\Administrator\.m2\repository' `
  '-Dlaunch4j.skip=true' -DskipTests -pl forge-gui-desktop -am package
```

### 8.2 Windows 中文路径问题

Launch4j 的 `windres.exe` 在包含中文的工作区路径下可能失败。推荐在纯 ASCII 路径的工作树中正式构建；也可跳过 Launch4j，复用已验证的无代码启动器外壳，但必须读取 EXE 内嵌字符串，确保包内 JAR 文件名完全一致。

已经发生过的故障：复用的 `forge.exe` 内写死 `forge-gui-desktop-2.0.15-cn0812-jar-with-dependencies.jar`，包内却命名为 `cn0813`，双击后没有任何提示。发布前必须执行：

```powershell
rg -a -o 'forge-gui-desktop-[0-9A-Za-z._-]+jar' .\forge.exe
```

结果必须与目录中的 JAR 文件名逐字一致。随后实际启动并确认内置 `runtime/bin/javaw.exe` 子进程存在。EXE 外壳自行退出而 `javaw.exe` 继续运行属于正常行为。

Windows 完整包应包含 `forge.exe`、匹配的主 JAR、`runtime`、`res`、配置示例、使用说明和更新说明。用户必须完整解压，不能在 ZIP 内运行。

### 8.3 Android D8 命令过长

老 `android-maven-plugin` 在 Windows 上把全部 classpath 展开成一个 D8 命令，容易超过系统命令行长度。常规 Maven 流程通常能完成 Java 编译、资源打包和 ProGuard，然后在 D8 阶段失败。

上述流程已固化为一键脚本：

```powershell
.\deploy\build-android-community.ps1
```

脚本自动完成以下工作：

1. 读取根 `pom.xml` 的 Maven revision、显示版本和 Android `versionCode`；
2. 在同一个 Maven reactor 中增量编译 Android 及其依赖模块；
3. 让旧插件完成 Manifest 合并、资源处理和 ProGuard；
4. 将仓库临时映射到纯 ASCII 短盘符，把 D8 参数写入 `@args` 文件，接管会失败的 D8 阶段；
5. 重新组装 dex、四 ABI 原生库、CJK 字体、fallback 启动资源、中文本地化和 tinylog 服务描述符；
6. 执行 zipalign、APK 签名、签名验证、版本校验和关键文件清单校验；
7. 将最终文件、SHA-256 和构建元数据输出到 `dist/android/`。

第一次完整构建仍需编译 Forge 的依赖模块，会花较长时间。后续不要主动删除各模块 `target`，Maven 会复用已编译结果，构建会明显加快。只有构建缓存异常或确实需要回收磁盘时，才运行第 11 节的清理脚本。

默认使用当前用户的 `.android/debug.keystore`，以保持现有测试版可覆盖安装。正式换用长期发布密钥时可传入 `-Keystore`、`-KeyAlias`，并通过环境变量 `FORGE_ANDROID_STORE_PASSWORD`、`FORGE_ANDROID_KEY_PASSWORD` 提供密码；密钥文件和密码不得提交 Git。更换签名密钥后，旧版用户无法直接覆盖安装，因此发布密钥一旦确定必须长期备份。

如工具不在默认位置，可以显式传入：

```powershell
.\deploy\build-android-community.ps1 `
  -JavaHome 'C:\Program Files\Java\jdk-21.0.10' `
  -AndroidSdk "$env:LOCALAPPDATA\Android\Sdk" `
  -Maven '.\.tools\apache-maven-3.9.12\bin\mvn.cmd'
```

参数文件中包含版本化模块 JAR 路径。版本更新后若复用旧 args 文件，必须替换所有旧 `cn...` 版本，否则会把旧代码打进新 APK。

APK 最终检查至少包括：递增的 `versionCode`、正确 `versionName`、dex、中文映射、镜像配置、发布说明、CJK 字体、四 ABI FreeType 库、日志服务以及与上一正式版一致的签名。

## 9. 阿里云发布

详细基础设施配置见 `deploy/aliyun/README.zh-CN.md`。生产发布遵循“不可变文件先上传，清单最后上传”，客户端不能看到指向尚未上传完成文件的清单。

```powershell
.\deploy\aliyun\publish-clients.ps1 `
  -ManifestVersion '2.0.15-cn0813r2' `
  -DesktopVersion '2.0.15-汉化-08.13.2' `
  -ObjectVersion '2.0.15-cn-08.13.2' `
  -Bucket 'forge-cn-update-a8k3' `
  -AndroidApk '.\Forge-Android.apk' `
  -DesktopPackage '.\Forge-Windows.zip' `
  -Ossutil '.\.tools\ossutil\ossutil-2.3.0-windows-amd64\ossutil.exe'
```

规则：

- 安装包和资源包使用带版本号的不可变路径，清单短缓存并最后更新；
- 同一路径上传过错误文件后不要覆盖并期待 CDN 立刻变化，应换新对象版本，如 `desktopfix1`；
- 发布脚本计算大小和 SHA-256，并保留未变化的 `assets.*` 字段；
- 不要使用 `sync --delete` 发布卡图；
- 资源包和卡图分别使用 `publish-assets.ps1`、`publish-card-images.ps1`。

### 发布后公网验收

不能只看 ossutil 上传成功。必须从 CDN 回读并确认：清单无 UTF-8 BOM；三个对象返回 `200`；`Content-Length`、类型和 SHA 正确；APK `versionName == android.version`；桌面 JAR `Implementation-Version == desktop.version`；新装、覆盖安装和完全重启后均不重复提示同版本更新。

OSS Bucket 私有时，RAM 发布账号可能有上传权限却没有读取对象 ACL 的权限，`ossutil stat` 因 `?acl` 返回 403 不代表对象不存在。可用 `ossutil cp` 回读清单，再通过 CDN HEAD/GET 验证对象。

### 回滚

不要删除已发布的版本对象。回滚只需让 `manifest-v1.properties` 重新指向已验证的旧对象，再刷新或等待清单短缓存。OSS 版本控制应保持开启。

## 10. 发布检查清单

- [ ] Git 工作区干净，`git diff --check` 通过；
- [ ] 三套版本均更新，Android code 单调增加；
- [ ] 中文占位符和 UTF-8 正常；
- [ ] 卡名文件无重复键、空键和规则截断；
- [ ] CJK 覆盖审计为 0；
- [ ] Maven mobile/desktop 编译通过；
- [ ] Android 实体设备完成中文选词和提交；
- [ ] Android 首次授权、资源下载、第二次启动正常；
- [ ] APK 关键资源、签名和 badging 校验通过；
- [ ] Windows EXE 内嵌 JAR 名与实际文件一致；
- [ ] Windows 使用内置 runtime 启动；
- [ ] 安装包和说明明确完全免费、民间非官方、QQ群；
- [ ] 上传不可变对象后最后更新清单；
- [ ] CDN 公网大小、SHA、版本与启动更新行为全部验收。

## 11. 磁盘清理

构建产物不应无限期散落在各模块 `target`，但这些目录也是自动打包的增量缓存。日常开发应保留它们以提高构建速度；确认正式包已上传并另行归档、磁盘空间不足或缓存异常时再使用：

```powershell
.\deploy\cleanup-development-artifacts.ps1
.\deploy\cleanup-development-artifacts.ps1 -Execute
```

第一次只预览。`-Execute` 仅删除当前仓库内名为 `target` 的目录，不删除源码、`.git`、`.tools`、Maven 仓库、Android SDK、ossutil 配置或阿里云对象。正式发布的权威副本是 OSS 中带版本号的不可变对象。

## 12. 关键教训

1. APK 能安装不代表首启资源完整；黑屏常来自字体、皮肤或原生库缺失。
2. 键盘能弹出不代表中文能提交；必须实际选择候选词。
3. 版本代表同一版不代表字符串相等；Android 必须使用 APK 精确 `versionName`。
4. EXE 存在不代表能找到主 JAR；Launch4j 的 JAR 名可能写死。
5. OSS 上传成功不代表 CDN 用户拿到新文件；错误路径应换版本，清单必须最后切换。
6. 修一个缺字不是字库方案；应审计全部本地化数据并使用增量字体。
7. PowerShell 默认 BOM 会破坏 Java Properties 的第一个键；清单必须为 UTF-8 无 BOM。
8. 所有清理应限定在已解析的仓库路径内，并默认 dry-run。

## 13. 中文套牌导入与首启提示（2026-09-16）

桌面 `FView` 与移动端 `Forge`、`SplashScreen` 已移除首次启动下载英文卡图索引的弹窗入口。同步上游时注意保留此调整，不能仅依靠本机缓存或“已询问”标记隐藏弹窗；手动卡图下载功能保持原有行为。

两端的套牌导入共用 `DeckRecognizer`。牌名语法接受 Unicode 字母、组合字符与中文标点；`CardTranslation` 从随包的 `cardnames-zh-CN.txt` 建立中文名反查索引，英文界面也可导入中文。名称按 NFKC 规范化以兼容全角标点，双面牌的独立牌面会映射回所属卡牌。英文名称和原有数量、系列、收藏编号、备牌语法继续可用。

不同卡牌使用同一中文翻译时不任意选择，而是保留为未识别项；翻译文件缺失时英文导入仍可用。此索引只读取本地随包文件，不增加客户端下载或启动联网。

回归测试：`ChineseDeckImportTest`、`DeckRecognizerTest`、`DeckRecognizerPortablePatternTest`，共 88 项通过。覆盖中英文界面、混合牌名、双面牌正反面、备牌及系列编号、同名歧义、缺失翻译文件，以及随包中文牌名字符（ASCII 圆括号仍保留上游作为系列分隔符的限制）。

## 2026-09-22：安卓离线启动与中断恢复

- APK 版本和资源版本不同，断网时不能拿 APK 版本比较资源的 `version.txt`。本地是否可启动，必须独立于服务器清单、皮肤缓存列表和可选冒险背景图。
- `AndroidStartupFiles` 检查核心皮肤、语言、系列、衍生物脚本以及卡牌脚本包。版本标记缺失或为空不等于没有资源；`.resources-installing` 表示解压曾被中断，不能作为成功安装启动。
- 语言文件和字体在启动图初始化前安装。先比较 SHA-256，相同则不重写；不同则写同目录临时文件、同步落盘、核验并原子替换。不能直接 `copyTo` 覆盖正在使用的文件，也不能只按字体长度判断完整性。
- 初始字体缓存清理在 GL 线程、创建字体对象前完成；后台更新检查不再刷新正在渲染的字体。启动图加载异常需记录，并使用 APK 自带启动图兜底，不能静默停在黑屏。
- 资源下载成功后、开始解压前才写安装中标记；下载/解压失败不能更新版本号或自动重启为成功。解压成功、核心资源检查通过后才写版本并移除标记。
- 单测覆盖离线资源判断、空版本标记、首次无资源、损坏卡牌 ZIP、中断解压、同长度文件损坏、拷贝失败保留旧文件、遗留临时文件、解压失败结果。
- 实机矩阵：已有完整资源时飞行模式冷启动；断网时多次强制结束重开；平板横屏；联网后清单连接失败；下载取消/磁盘不足；缺资源时应提示恢复而不是进入损坏牌库。桌面单测不能替代这些设备测试。

本次从独立项目发布分支 `zh-cn-card-translations` 同步卡牌翻译，固定来源：
`https://raw.githubusercontent.com/kaorou-bot/forge/refs/heads/zh-cn-card-translations/forge-gui/res/languages/cardnames-zh-CN.txt`。
该文件不是 Forge 上游开发分支维护的翻译。本地由 38,069 条更新至 38,219 条，新增 150 条、修改 43 条；远端原始 SHA-256 为 `352bb536a563337a2a9e265c5b161f2418a8bf9df8d3e8f02b6c7e6071272582`，这里只记录本次来源，不作为以后下载的固定哈希。
已验证严格 UTF-8、前三个竖线分栏、英文键非空且唯一，规范化 LF 后与来源一致；CJK 覆盖 3,926 个码点，无缺字。
翻译仍在构建/发布时同步，客户端不增加翻译下载功能。Android APK 的 `assets/localization/cardnames-zh-CN.txt` 会覆盖本地翻译，故翻译更新需要新 APK，不能只发布 `assets.zip`。

### 验收与 cn0922r2 发布

- 98项针对性测试通过，零失败；自动脚本生成的测试APK已安装到模拟器。
- 模拟器一度在解压100%后提示失败：日志显示20,377项无法解压。包大小和SHA-256与CDN相符，但旧资源由ADB shell写入，应用没有覆盖权限。将旧res及根目录资源元数据改名备份，保留data和cache，再让应用下载解压后正常进入模式选择界面。不要把此权限问题误诊为下载失败，也不要清空用户存档。
- 用户确认可以并要求发布。正式版本cn0922r2，Android versionCode递增至2026092202；桌面显示版本2.0.15-汉化-09.22.2。设备表现应按实际测试记录，不宣称所有平板机型均已验证。
- 保留现有独立资源包版本2.0.15-cn.assets.20260922；不修改大厅或中继服务。

## 2026-09-22：tokens 独立图源（cn0922r3）

- 普通卡图的`images.baseUrl`不作用于tokens。两端共用`ImageFetcher`，从`tokens.baseUrl`读取独立镜像，之后保留`token-images.txt`原图源和Scryfall候选。不能只上传图片却不接入客户端路由。
- 当前图源为`https://images.mtg-forge-kaorou.vip/tokens/tokens-20260922-181200/`。使用版本化目录避免覆盖旧图和CDN长缓存；普通卡图目录不变。
- 原样保留`系列/编号_脚本名.jpg`与`☇`，按UTF-8编码URL，不添加`.fullborder`。社区URL不可应用旧图源的删下划线后缀重试，避免把token名称或编号截断。
- 2,954张图片：中文1,054张、英文1,900张，最高488px高。66条缺图、249张未被当前Forge登记的Scryfall印刷不在已覆盖范围。既有本地图片不自动覆盖。
- 首次上传曾遇Bucket ACL的403，未发布指向不可用地址的客户端。管理员部署后，逐张公网读取全部图片并验证大小、SHA-256和JPEG头，2,954/2,954通过，含15条Unicode背面标记路径。
- 新增镜像优先级、原来源保留、缺少旧映射、特殊字符编码、异画编号不合并测试；连同既有启动与导入测试共102项通过。
- APK配置在`assets/update-mirror/forge-update.properties`，桌面配置在主JAR内。必须发布新客户端才生效，单独更新安卓资源包不会改变下载路由；本次保留现有资源包。
- 安卓并不通过桌面classpath读取该配置；新增配置键时必须同时在`forge-gui-android/.../Main.configureUpdateMirror()`中将其传入系统属性。tokens对应`tokens.baseUrl` → `forge.tokens.url`，不能只检查APK里存在properties文件就宣称路由生效。
- 部署脚本：`deploy/aliyun/publish-token-images.ps1`与`verify-token-cdn.ps1`；权限和路径详见同目录`Tokens-20260922-交接.md`。联机大厅无需修改或重启。

## 2026-09-22：桌面正式版 FRA 现开牌池缺失

- 新卡脚本和 FRA 系列定义已经包含在桌面包中，问题不是资源漏上传。`CardStorageReader.collectCardFiles()` 原先在非开发版本跳过 `upcoming`；安卓从 `cardsfolder.zip` 读取，没有对应过滤，导致同一资源两端牌池不同。
- 旧正式 JAR 实测：`upcoming` 加载路径为 0，FRA 主系列 71 张普通牌中只有 Unsummon、Last Gasp、Blazing Crescendo 的脚本可加载，另外 68 张被跳过。
- 修复目录扫描，加载所有随包提供的新卡，与 ZIP 路径一致；保留隐藏目录过滤，不修改全局 `BuildInfo.isDevelopmentVersion()`，也不改变版本号、图片、翻译或联机服务。
- `ReleaseUpcomingCardsTest` 强制非开发条件，覆盖全量 FRA 普通牌、延迟加载新杰斯、六包现开（每包 14 张及新普通牌）。三项测试在修复前全部失败，修复后与延迟加载、中文导入、tokens、启动和更新清单测试合计 109 项通过。
- Maven 的 `target/classes` 没有正式 JAR 的 Implementation-Version，通常被识别为 GIT；不能只跑默认开发模式测试。打包后还需使用实际发布 JAR，在非开发模式下检查牌池和生成六包现开。
- 本次实际新构建的正式格式 JAR 验证通过：开发模式为 false，71 张 FRA 普通牌全部存在，六包共 84 张、该次抽样 71 个不同牌名。验证使用既有 cn0922r3 资源，进一步确认不需要重新下载卡牌资源即可修复加载问题。
- 独立桌面测试包输出至 `dist/desktop-cn0922r3-fra-fix/`，用包内 JRE 和资源重复验证亦通过（71 张普通牌、六包 84 张、该次抽样 69 个不同牌名），ZIP 完整性检查通过。测试包沿用 cn0922r3 标识，未作为新版本部署；后续正式发布必须递增版本并使用新路径。
- 修复只对之后重新生成的牌池生效，不会把之前已经保存的异常现开套牌自动重抽。测试包和线上 cn0922r3 包必须分目录保存，不覆盖已经发布的同版本对象。

### 用户验收后发布 cn0922r4

- 用户确认桌面 FRA 测试正常，授权递增版本、提交推送并发布。桌面显示版本为 `2.0.15-汉化-09.22.4`，构建标识 `2.0.15-cn0922r4`；提供完整 EXE、内置 JRE 的 ZIP，以及仅适用于 `2.0.15-汉化-09.22.3` 的增量 EXE。
- 正式版本重新执行 109 项测试，零失败；使用新包自带 JRE 验证 71 张 FRA 普通牌和六包共 84 张牌，ZIP 完整性检查通过。中文卡牌翻译与固定独立发布分支逐字比较一致，仍为 38,219 条。
- 本次仅发布桌面：必须原样保留清单全部 `android.*` 和 `assets.*` 字段，尤其不能将 `android.version` 改为 cn0922r4，否则会诱发重复更新。原 APK cn0922r3 和资源版本 `2.0.15-cn.assets.20260922` 不变。POM 为下一次 Android 构建预留更高 versionCode，不代表本次已发布新 APK。
- 不使用强制要求同步 Android 的 `publish-clients.ps1` 直接发布本次桌面热修复。备份线上清单，先上传新版本目录中的不可变制品并从 CDN 完整下载比对大小与 SHA-256，切换前再次核对清单未被其他任务修改，最后只更新桌面相关字段及整体发布元数据。
- 不修改、重启联机大厅或中继服务。Git 仅推送自己的 `origin/zh-cn-community-release`，不向上游发起 PR。
