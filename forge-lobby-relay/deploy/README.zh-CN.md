# Forge 民间汉化版：自建大厅与中继服务端

本软件完全免费，按随包 GPL-3.0 许可证提供，不是 Card Forge 或 Wizards of the Coast 官方服务。
维护交流 QQ 群：813597628。汉化项目：https://github.com/kaorou-bot/forge

## 先选择用途

- **朋友局域网直连**：不需要此服务端。两端选“直连”，房主建房，客机输入房主的 IP:36743。公网直连需房主具备可入站地址并配置防火墙/路由器映射，普通 CGNAT 不适用。只与可信玩家分享直连端口。
- **自建大厅服务器**：部署本包，然后双方在游戏中选“服务器连接”，点击“大厅服务器”填写 `tls://你的域名:443`。留空恢复默认社区服务器。不同服务器的房间列表不互通。
- 本服务端只提供房间目录和网络中继，不执行卡牌规则、不替代房主。房主退出后房间及对局不能由服务器接管。支持客户端创建 2–8 人房间，兼容代号由客户端传入；不同版本玩家仍建议安装完全相同的客户端。

## 包内内容与要求

`forge-lobby-relay.jar` 为包含依赖的独立服务端；无需完整 Forge、卡图或资源包。
需要自行安装 Java 17 或更新的运行环境（推荐 Java 21 JRE）；编译源码才需要 JDK 与 Maven。
含 Linux systemd 服务文件、Nginx TLS 示例、Windows/Linux 本机启动脚本、源码构建目录及 SHA256SUMS。
包中不含任何生产密钥、证书、云凭据或线上用户数据。`VERSION.txt` 标注构建及源码基线，`source/` 包含该服务端与协议模块的实际源码、测试和构建配置。

## 本机快速检查（不向公网开放）

解压至独立目录，在该目录打开终端：

Windows PowerShell：`powershell -ExecutionPolicy Bypass -File .\start-local.ps1`

Linux：`sh ./start-local.sh`

另开终端请求 `http://127.0.0.1:36745/health`，正常返回 `status: ok` 及房间、连接数量。
同一台电脑上的新客户端填 `tcp://127.0.0.1:36744`，可建立本机测试房间。此地址不是手机可访问的服务器地址。
按 Ctrl+C 停止前台服务。Windows 脚本支持 `-Port` / `-HealthPort` 以避开占用。
默认两个监听端口均绑定回环地址，不能直接给其他设备使用；公网部署必须完成下一节。

## 公网部署（Ubuntu + systemd + Nginx）

先准备自己的云主机、域名 A/AAAA 解析和该域名的有效公共 TLS 证书。不要使用社区服务器域名或证书。
以下命令针对一台没有既有 Forge 服务的新主机；已有业务请先备份配置，勿覆盖、停机或抢占端口。

1. 安装运行依赖：`sudo apt update`，然后 `sudo apt install openjdk-21-jre-headless nginx libnginx-mod-stream curl`。检查 `java -version` 与 `nginx -T`，确认 stream 模块已加载。部署者自行申请证书（例如 Certbot），并保证续期后 reload Nginx。
2. 将本包上传解压，在解压目录执行：

```sh
sudo useradd --system --no-create-home --shell /usr/sbin/nologin forge-relay
sudo install -d -o root -g root -m 0755 /opt/forge-relay
sudo install -o root -g root -m 0644 forge-lobby-relay.jar /opt/forge-relay/forge-lobby-relay.jar
sudo install -o root -g root -m 0644 forge-lobby-relay.service /etc/systemd/system/forge-lobby-relay.service
sudo systemctl daemon-reload
sudo systemctl enable --now forge-lobby-relay
sudo systemctl status forge-lobby-relay --no-pager
curl --fail http://127.0.0.1:36745/health
```

若用户已存在，先确认它确为专用非登录用户，再跳过 useradd；若目标 JAR/服务已存在，先按下文升级流程维护，不要直接覆盖。

3. 将 `nginx-self-hosted.conf.example` 内域名和证书路径替换为自己的值，把 `stream { ... }` 合并到 `/etc/nginx/nginx.conf` **顶层**。不能直接放入默认的 HTTP `conf.d` 或 `sites-enabled`，也不能用网页 `location` / WebSocket 代理。若已有 stream 块，只合并其中 server；不要再套一层 stream。
4. 443 如已被网站/其他进程占用，选择空闲端口如 8443，并在客户端填写相同端口。不要停掉现有网站硬抢端口。
5. `sudo nginx -t` 成功后再 `sudo systemctl reload nginx`。云安全组和主机防火墙只放行选定 TLS 端口（443 或 8443）；不要开放 36744、36745。SSH 仅给管理员 IP。证书申请若用 HTTP-01，需要另行安排 80 端口与续期；中继不是 HTTP 服务。
6. `ss -lntp` 确认 Java 仅监听 `127.0.0.1:36744/36745`，Nginx 监听公网 TLS 端口。验证证书：

```sh
openssl s_client -connect relay.example.com:443 -servername relay.example.com -verify_hostname relay.example.com -verify_return_error </dev/null
```

替换自己的域名。证书验证错误必须修正，不能关闭客户端校验。浏览器/curl 打开公网 TLS 端口不会出现网站页面，这是二进制 TCP 服务。

7. 两个新客户端均选“服务器连接”并填写自建地址；依次验证刷新列表、创建房间、另一设备加入、开始一局、退房、房主离线后房间清理。仅 health 成功或能刷新列表，不代表长连接转发已经验证。

Nginx 配置依据：[stream TLS](https://nginx.org/en/docs/stream/ngx_stream_ssl_module.html)、[TCP 代理](https://nginx.org/en/docs/stream/ngx_stream_proxy_module.html)。

## 运维、升级与限制

- 状态：`systemctl status forge-lobby-relay`；日志：`journalctl -u forge-lobby-relay -n 100 --no-pager`。日志可能含 IP/房间信息，分享前脱敏；不要分享私钥。
- 重启会断开房间和对局！先通知玩家、确认 health 中 rooms 和 connections，再安排维护。停止服务、备份旧 JAR、替换新 JAR、启动、检查 health 和客户端建房加入；失败时停止并恢复旧 JAR。Nginx/证书变更也要先评估影响。
- 房间只在内存中保存，无账号数据库/持久化对局，不支持重启恢复。流量由服务端转发，需监控云带宽、出网费用、连接数和内存；本包未承诺特定并发容量。
- 服务包含连接数、握手帧大小和操作频率限制，但不替代云防护；TLS 在 Nginx 终止，服务端看到的源地址可能是回环地址，应用每 IP 限制因而可能是共享限额。公开运营需根据实际使用评估边缘限流、防滥用及监控，不要盲目关闭限制。
- `forge.relay.bind/port` 与 `forge.relay.health.bind/port` 可通过 Java `-D` 设置（必须放在 `-jar` 前），生产环境保持回环监听。修改端口同时调整 systemd、Nginx 与监控。
- 客户端旧的 `-Dforge.relay.host/port/tls` 开发覆盖参数仍优先于界面地址；一般玩家不要设置它们。如修改界面后仍连接旧地址，请移除这些启动参数。

## 从源码构建

在 `source` 目录安装 JDK 21 与 Maven 3.9 后执行：

```sh
mvn -Dcheckstyle.skip=true test package
```

输出在 `forge-lobby-relay/target/*-jar-with-dependencies.jar`。本包 source 的聚合 POM 只保留协议与服务端两模块，不要求下载卡牌资源或 Android SDK。完整项目构建和打包命令为 `deploy/build-relay-package.ps1`；所有脚本仅生成本地包，不连接或重启远程服务器。
