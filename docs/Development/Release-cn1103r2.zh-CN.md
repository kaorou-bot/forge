# cn1103r2 发布记录

2026-11-03 用户授权更新版号、打包并部署更新服务器。本次发布 Round41–42 的全部内置冒险物品汉化，不合并额外上游功能，不改变卡牌规则。

- 基线：bb9e8b08aae050b40b9dff652378a188b50cb1c3。
- 构建标识：2.0.15-cn1103r2；显示：2.0.15-汉化-11.03.2；Android versionCode：2026110302。
- 共用资源身份：2.0.15-cn.assets.20261103r2。
- Android APK、Windows 完整 EXE、内置 Java 21 JRE 便携 ZIP 和共用资源 ZIP 必须配套发布，不制作增量包。
- 独立中文卡表重新 fetch 核对：zh-cn-card-translations 提交 b830c8435e6d345ee0ec02642010da62e4a475c6，文件 Git blob 7bed46b2863fbae153c690da66a981277e88dd52，与本地一致；不更换来源，不增加客户端下载。

## 验证与边界

Round42 功能源码已独立验证：r2 全量 81 项通过、Android 10 模块编译及 Checkstyle 通过；r3 最终一条说明修订后新跑 9 项，其他 72 项和编译明确继承 r2。全部 1,231 定义、950 名称、1,059 描述变体、语言格式和字体覆盖已检查。详见 Adventure-Localization-Review.zh-CN.md 和 dist/independent-network-tests/round42-all-adventure-items/final-report.txt。

本次新版本构建和制品验收交由同一独立测试任务 01a12110-42fb-7a42-97a8-a074bf5df870 执行 Round43，正式制品集中在主工作区 dist/releases/cn1103r2，资源包为 dist/releases/shared/assets-cn1103r2.zip。源码和固定开发树缓存串行交接，不触碰主工作区历史修改。

先上传不可变版本对象并完整 CDN 回读校验，再提交推送对应源码，核对原清单未并发变化后切换更新清单；保留未知字段，移除旧增量入口。不上线/重启大厅，不改变卡图、tokens、自建服务端包或皮肤资料包。

未覆盖实机、模拟器、界面交互、完整历史存档加载和所有玩法。已存在的 c_a_gold_draw 引用缺失继续记录为旧数据限制，不在物品翻译发布中替换玩法资源。最终上线状态和哈希以 publication-records 为准。

## Round43 正式制品验收

独立测试已完成并交还工作树和缓存。24 个冻结文件、完整 diff、分支及基线提交均未变化；测试任务未修改产品源码。报告位于固定开发树 `dist/independent-network-tests/round43-cn1103r2/final-report.txt`。

- 本轮重跑 13 项发布测试，全部通过，无失败、错误或跳过；相关模块 Checkstyle 通过。
- APK 的版本、签名连续性、对齐、CRC、实际 DEX 兼容性及新物品逻辑检查通过。
- Windows 便携包 56,156 个文件完整校验，资源与源码一致；启动器指向本次 JAR，内置 Java 21.0.10 JRE。完整安装器编译及版本检查通过。
- 资源包含 21,795 个外层资源文件和内嵌 34,137 个卡牌文件，均与源码一致。新 JAR 的真实解压代码成功解出 21,802 个文件，逐项 SHA-256 一致，启动资源可用性检查通过。
- TTF 所需 4,060 码点、像素字体所需 3,891 汉字无缺失或空白。
- 81 项功能基线在受测文件逐字节一致后继承 Round42，不宣称在 Round43 重跑；新制品检查均为本轮执行。

| 制品 | 字节数 | SHA-256 |
| --- | ---: | --- |
| Android APK | 21532709 | `80a20b2e631cda1e4405c153bf218edbec0b4cc8da0dab3609e7787c43e49483` |
| Windows 便携 ZIP | 311045057 | `edd65f4cac96bc2de213460682efde64d4e6efa2f753f51551c6c73ca7d17cdf` |
| Windows 完整 EXE | 263082175 | `f0ef3f55f1c271c33f7eb3f4ad1c9c3c85bf2a0ae6fb4741f3e72a35c0ba0752` |
| 共用资源 ZIP | 193860091 | `c44e0467414991695e7a79e709307e0a301031edcc931bc2fddc3eafa02720cc` |
| 发布说明 | 38785 | `5cd74cee4735b143ba6a8d0a86cf323e11d8f474b8d0a9f6d4de88f1826c11da` |

发布阶段仅补充本记录，不改变已经冻结并验收的产品源码或打包资源。线上对象、清单切换回读及对应源码提交保存在主工作区 `dist/releases/cn1103r2/publication-records`。
