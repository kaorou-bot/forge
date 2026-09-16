# Windows 安装包与按文件增量更新

## 发布结构

桌面完整安装包使用 Inno Setup，按用户安装到 `%LOCALAPPDATA%\Programs\ForgeCN`，包含 `forge.exe`、版本化 JAR、`res` 和内置 Java 21 JRE。安装目录的 `forge-community-version.txt` 是增量更新基线标记。用户套牌、存档和卡图缓存通常位于 `%APPDATA%\Forge` 与 `%LOCALAPPDATA%\Forge\Cache`，不在安装器覆盖范围内；若用户自定义了 `forge.profile.properties`，安装器也不得覆盖该文件。

增量包是另一个安装程序，只含与指定上一版相比新增或变化的文件，并删除上一版已废弃的受控程序文件。安装前必须确认安装目录的版本标记与 `desktop.patch.from` 相同。便携 ZIP、缺少安装器卸载程序或版本标记、跨版本或安装目录不可写时，客户端只使用完整安装包。

`manifest-v1.properties` 保留 `desktop.*` 为完整安装包。发布增量包时额外写入：

```properties
desktop.patch.from=上一版中文显示版本
desktop.patch.version=新中文显示版本
desktop.patch.url=desktop/版本/补丁.exe
desktop.patch.size=字节数
desktop.patch.sha256=SHA-256
```

客户端在下载后先校验大小和 SHA-256，只有校验通过才运行安装器。OSS 发布顺序必须是完整包、增量包、最后更新清单。上架新机制的第一版只有完整安装包；从下一版开始才可产生增量包。旧版客户端下载新完整安装包后可能只打开下载目录，用户需手动运行一次安装程序。

## 构建

先运行 `deploy/build-desktop-community.ps1`。它仍提供便携 ZIP，并在解压目录写入版本标记。之后运行：

```powershell
.\deploy\build-desktop-installer.ps1 `
  -PackageDirectory '.\dist\desktop\Forge-当前版本-Windows' `
  -OutputDirectory '.\dist\desktop-installers'
```

从第二个安装包版本起，可追加 `-PreviousPackageDirectory`、`-PreviousVersion`（上一版中文显示版本）和 `-PreviousBuildId`（上一版 ASCII 构建版本），同时生成增量安装器。构建机需安装 Inno Setup 6；编译器路径可用 `-Compiler` 指定。

发布脚本 `deploy/aliyun/publish-clients.ps1` 使用 `-DesktopInstaller` 代替原 `-DesktopPackage`；有增量包时再传 `-DesktopPatch` 和 `-PatchFromVersion`。不要用同一个版本号覆盖已公开的安装包或清单；应升级 Android 与桌面版本后一起发布。

## 验收与回退

在独立临时目录验证首次安装、快捷方式、内置 JRE、启动、退出、卸载和套牌保留。使用与发布版相同的上一版完整安装包验证增量更新，并确认下载量明显小于完整包。错误基线、缺少版本标记或校验失败必须拒绝增量包并能转用完整安装包。发布前对 CDN 清单和两个安装包做 HEAD/哈希核对。

增量安装可能因断电或杀毒软件锁文件而中途失败，因此必须保留完整安装包供修复安装；正式发布时不要移除上一版安装包。若新清单出现问题，恢复 OSS 中上一版清单并刷新 CDN 缓存。中继服务器与这套客户端安装流程无关。
