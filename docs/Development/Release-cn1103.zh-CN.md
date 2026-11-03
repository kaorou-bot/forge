# cn1103 发布记录

用户于 2026-11-03 授权更新版号、打包并部署更新服务器。

- 构建基线：`e46dc57a7b3f6d9ce37d493a577b5cd4205b4244`，已包含上游 `cf7663d79e98e6fd96621d1340dfe793ca544fc8`、冒险中文换行修复和本轮新增内容汉化。
- 版本 `2.0.15-cn1103`；显示版本 `2.0.15-汉化-11.03`；Android versionCode `2026110301`。
- 共用资源身份 `2.0.15-cn.assets.20261103r1`；必须与客户端一起发布，保证新地图绑定与语言表相符。
- 输出：Android APK、Windows 完整安装器、内置 Java 21 JRE 的便携 ZIP、共用资源 ZIP；不制作或推送增量包，更新清单不保留旧版增量入口。
- 中文卡表仍来自独立项目分支 `zh-cn-card-translations`，提交 `b830c8435e6d345ee0ec02642010da62e4a475c6`。保留原始 UTF-8/LF 字节，解析只按前三个管道分四栏，不修改其内容或来源。

## 工作树与验证

复用固定开发树 `mtg forge 汉化-android-language-fix-20261008`。原七份未提交换行改动逐文件核对与已发布源码一致后，定向存入 Git stash `7cf27496ed46ea46d7df3a7bb85aab142afe6ca3`，随后快进到上述构建基线。该备份不包含其他本地文件；未跟踪的 `AGENTS.md`、`deploy/VerifyCjkFontCoverage.class` 继续保留。

主工作区的历史修改与冲突不动。正式制品集中在主工作区 `dist/releases/cn1103/`，资源包在 `dist/releases/shared/assets-cn1103.zip`。

Round39 已验证基线的 Android 编译、138 项源码测试和资源审校。发布候选仍须由独立任务 `01a12110-42fb-7a42-97a8-a074bf5df870` 执行 Round40 构建/制品验收，核对递增版号、APK 签名与 DEX API 门禁、JRE/启动器、三份本地化资源、完整目录 ZIP 与内层卡牌数据。不得用旧包验证代替本次制品检查。

所有构建串行、冻结源码。使用既有 Android/desktop/installer 打包脚本及旧版已验证资源 ZIP 目录策略。未覆盖实机、模拟器、GUI/完整剧情及线上联机。Round39 的本机默认 AF_UNIX 环境限制见 `Adventure-Upstream-Sync-20261103.zh-CN.md`，不向发行包植入测试参数。

## 上线规则

先完成独立制品验收，上传不可变版本对象；从公开 CDN 全量回读验证大小和 SHA-256。提交并推送对应源码，检查远端更新清单未被并发改动后，再一次切换客户端和资源包字段，并回读核对。

不部署、替换或重启线上大厅/中继服务；不修改卡图、tokens、皮肤资料包。最终制品哈希和线上证据归档至 `cn1103/publication-records/`。

## Round40 验收结果

独立测试任务于 2026-11-03 完成本轮构建并交还源码及缓存。报告为固定开发树 `dist/independent-network-tests/round40-cn1103/final-report.txt`，产物清单见同目录 `artifacts.json`。

- 本轮新执行 13 项发行测试全部通过：AndroidStartupFilesTest 9 项、CommunityUpdateManifestTest 1 项、ReleaseUpcomingCardsTest 3 项；编译和 Checkstyle 通过。
- Round39 的 138 项功能测试明确作为相同功能源码的基线继承，不计为本轮重跑；另核对 30 个源码/测试/资源文件，差异仅有 4 个 Java 文件的工作树换行符转换。
- APK 签名与 cn1027 相同；版号递增、CRC、zipalign、全部 DEX API 门禁、新换行字节码及打包字体/语言资源检查通过。
- Windows ZIP 的 56,156 个文件与解包目录逐一一致；当前源码资源、启动器 JAR 引用和 Java 21 JRE-only 检查通过。完整安装器编译和 PE 版本核对通过，未实际安装。
- 资源 ZIP 22,576 条目、774 显式目录顺序符合解压器要求；21,795 个外层源文件和内层 34,137 个卡牌文件全部与当前源码一致。
- CJK 所需 4,053 码点全部覆盖；像素字体所需 3,885 个汉字无缺字或空白。
- 未执行 GUI、实机、模拟器、完整剧情/玩法或线上联机测试。默认本机 AF_UNIX 限制继续保留，不把测试环境回退参数写入发行包。

| 制品 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Forge-2.0.15-cn1103-Android.apk | 21450789 | `4bcb42ed515c3ac4a438e8263624830bcb984fc93d6d12abb8d2207960a7fb44` |
| Forge-2.0.15-cn1103-Windows.zip | 310730411 | `c0967a50193ab313fa7f5f25b386f32226d8e0bc59aecec631e6301baa484554` |
| Forge-2.0.15-cn1103-Setup.exe | 263024770 | `6410efe7a28fa6d50cf2cda27dc5ec43e1e347e6404fe222e88590a699be0b95` |
| assets-cn1103.zip | 193756486 | `42c0149d4292afce35e678f3acaa7bd527c9a93b067fbdb55d6ce30573309e1c` |
| 发布说明.txt | 37640 | `c22f9713e27365b7e0cce0dd4c7f4e36ed5e0e6fa0385e02f64865c7dac29dc3` |

验收后只补充本文档，不改动打包源码和发布说明。线上切换结果以 `publication-records/publication.json` 及 OSS/CDN 回读记录为准，不能以本地构建成功代替上线成功。
