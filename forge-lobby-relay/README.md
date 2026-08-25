# Forge Lobby Relay

This module provides the central room registry and opaque TCP relay used by the
Chinese community build. Desktop and Android clients can create or browse rooms
without exposing a Forge port or exchanging IP addresses. The room owner still
runs the authoritative Forge game engine; this service only pairs connections
and copies bytes.

## Build and test

```powershell
.\.tools\apache-maven-3.9.12\bin\mvn.cmd -pl forge-relay-client -am test
.\.tools\apache-maven-3.9.12\bin\mvn.cmd -pl forge-lobby-relay -am -DskipTests package
```

The standalone artifact is written to:

```text
forge-lobby-relay/target/forge-lobby-relay-*-jar-with-dependencies.jar
```

## Local development

```powershell
java -Dforge.relay.bind=127.0.0.1 -Dforge.relay.port=36744 `
  -Dforge.relay.health.bind=127.0.0.1 -Dforge.relay.health.port=36745 `
  -jar forge-lobby-relay/target/forge-lobby-relay-*-jar-with-dependencies.jar
```

Desktop and Android community builds default to
`play.mtg-forge-kaorou.vip:443` with TLS. To use a locally started relay during
development, override the endpoint with:

```text
-Dforge.relay.host=127.0.0.1
-Dforge.relay.port=36744
-Dforge.relay.tls=false
```

The local monitoring endpoint is `http://127.0.0.1:36745/health`. It reports
service status, current room count, and current connection count.

## Production deployment

Public clients must never connect to the raw Java listener. Keep it bound to
loopback and terminate TLS at an edge proxy. Example systemd and Nginx stream
configurations are in `deploy/`.

1. Point `play.mtg-forge-kaorou.vip` to the relay server.
2. Install Java 17 or newer, Nginx with the stream module, and a valid public TLS
   certificate for the relay hostname.
3. Copy the fat JAR to `/opt/forge-relay/forge-lobby-relay.jar`.
4. Install `deploy/forge-lobby-relay.service`, create its unprivileged user, and
   enable the service.
5. Add the stream configuration to Nginx and expose only TCP 443 publicly.
6. Verify the local health endpoint and a TLS client connection before changing
   the defaults in `NetConnectUtil`.

The production endpoint was deployed on 2026-08-25. Nginx exposes TCP 443,
while the relay and health listeners remain restricted to loopback. Certbot
renews the public certificate; install a deploy hook that reloads Nginx after a
successful renewal.

```sh
install -d /etc/letsencrypt/renewal-hooks/deploy
printf '#!/bin/sh\nsystemctl reload nginx\n' \
  > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx
certbot renew --dry-run
```

The service enforces framed handshake limits, an explicit network compatibility generation,
password hashing, room/player limits, global and per-IP connection limits, and
relay write backpressure. Operational rate limits and traffic alerts should also
be applied at the host firewall or edge before a public launch.
