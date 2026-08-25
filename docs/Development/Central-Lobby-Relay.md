# Central Lobby and Relay Design

## Decision

The Chinese community build will add a central lobby and TCP relay while keeping
the existing Forge host authoritative for the game. The public service does not
run game rules. It registers rooms and forwards opaque Forge network frames
between the room owner and guests.

This is the first migration step away from manually sharing an IP address. A
dedicated authoritative game server is explicitly out of scope for this phase.

## User experience

1. The Online screen connects to the configured lobby service automatically.
2. Players can refresh a room list containing room name, owner, format, occupancy,
   password status, and compatible Forge version.
3. A player can create a public or password-protected room.
4. Guests join by selecting a room. They never enter an IP address or configure
   port forwarding.
5. Desktop and Android use the same lobby and relay protocol.
6. Direct IP hosting remains available as a diagnostic fallback during rollout.

The room owner still runs `FServerManager` and the game engine. If the owner
disconnects, the room closes and the match ends, matching current host behavior.

## Components

### Forge client

- `LobbyServiceClient`: authenticated control connection for listing, creating,
  updating, and joining rooms.
- `RelayHostAgent`: maintains the owner's outbound control connection. For every
  accepted guest, it opens one outbound tunnel and bridges it to the owner's local
  Forge server port.
- `RelayJoinConnector`: opens the guest side of a tunnel and gives `FGameClient`
  a connected byte stream.
- Desktop and mobile lobby views share a platform-independent room model.

### Public relay service

- Maintains the in-memory room registry.
- Issues cryptographically random room-owner and join tokens.
- Verifies client/protocol versions and optional room passwords.
- Requests an outbound tunnel from the room owner when a guest joins.
- Pairs the owner tunnel and guest tunnel, then forwards bytes without decoding
  Forge game objects.
- Applies connection, frame, bandwidth, and idle limits.
- Removes all rooms and tunnels when an owner control connection closes.

### Edge proxy

- Terminates TLS using a certificate for `play.mtg-forge-kaorou.vip`.
- Proxies only authenticated lobby and relay traffic to the service.
- Provides connection and request-rate limits before traffic reaches Java.

OSS and CDN continue serving installers, updates, resources, and card images;
they cannot host this stateful relay service.

## Connection sequence

```text
Room owner             Lobby/relay service              Guest
    | REGISTER_ROOM            |                           |
    |------------------------->|                           |
    | roomId + ownerToken      |                           |
    |<-------------------------|                           |
    |                          |<---------- LIST_ROOMS ----|
    |                          |---------- room list ----->|
    |                          |<--------- JOIN_ROOM ------|
    |<---- OPEN_TUNNEL(id) ----|                           |
    |                          |------ guest accepted ---->|
    | HOST_TUNNEL(id, token) ->|                           |
    |                          |<------ GUEST_TUNNEL -------|
    |<================ opaque Forge TCP frames ===========>|
```

The relay consumes only its small tunnel handshake. Once paired, all following
bytes are copied unchanged, so the existing `CompatibleObjectEncoder` and
`CompatibleObjectDecoder` stay at the Forge endpoints.

## Protocol boundaries

The control/tunnel protocol must not use Java object serialization. It uses a
small versioned binary or JSON protocol with explicit length limits. The existing
Forge serialization stream exists only inside an authenticated, paired tunnel.

Every request includes:

- relay protocol version;
- exact Forge network compatibility version;
- short-lived session or room token;
- request identifier for safe retries.

Room fields have fixed limits. Initial limits are 64 UTF-8 characters for names,
8 players per room, 30 seconds to complete a tunnel handshake, and an idle
timeout for unused rooms.

## Security requirements

- TLS is mandatory outside local development.
- Owner tokens contain at least 256 bits of randomness and are never shown in UI
  or logs.
- Guest join tokens are single-use and expire quickly.
- Passwords are stored only as salted password hashes.
- A guest cannot choose the destination host or port; the registry determines
  both ends of every tunnel.
- Room creation and join operations are rate-limited per IP and session.
- Tunnel byte and bandwidth limits protect the relay from unbounded buffering.
- Raw public access to the owner's Forge port is disabled when relay-only mode is
  selected.

## Version policy

The first public release requires an exact client build match. A compatibility
identifier is separate from the user-visible version so translation-only updates
can later remain network-compatible without weakening protocol checks.

## Rollout phases

### Phase 0: local vertical slice

- [x] One relay process, one owner, one guest.
- [x] Owner and guest both make outbound connections.
- [x] Complete one constructed match through the byte relay.
- [x] Confirm connection, disconnect, and cleanup behavior.

### Phase 1: usable private beta

- [x] Room list, create, join, leave, password, and occupancy.
- [x] Desktop and Android UI.
- [x] TLS client transport and edge termination configuration.
- [x] Tokens, version checks, connection limits, write backpressure, and health endpoint.
- [x] Safe operation audit logs and operation-level per-IP rate limits.
- [ ] Per-tunnel byte counters, external metrics collection, and alert rules.
- Direct-IP mode retained behind an Advanced action.

### Phase 2: public operation

- Monitoring, alerts, abuse controls, automatic service restart, metrics, and
  release/deployment automation.
- Load and soak tests with several simultaneous rooms.
- Operational runbook and user-facing troubleshooting guide.

## Acceptance criteria

- A desktop owner behind CGNAT can host an Android guest without port forwarding.
- An Android owner behind mobile data can host a desktop guest.
- Forge game traffic is byte-for-byte compatible with the current direct mode.
- An incompatible client cannot enter a room.
- A room is removed promptly after owner loss.
- A bad password or forged/expired token cannot open a tunnel.
- A failed or slow guest cannot block new joins or grow relay memory without bound.
- Server restart and owner/guest disconnects leave no stale rooms or tunnels.

## Known limitations

- The owner remains authoritative and must stay online.
- The relay cannot recover a match after owner process failure.
- The relay reduces NAT configuration problems but adds server bandwidth cost and
  one extra network hop.
- Spectating, matchmaking, accounts, rankings, match persistence, and dedicated
  game workers are not part of the first release.

## Verified implementation status (2026-08-25)

- Protocol, server, and client are separate Maven modules so Android does not
  package Netty server dependencies.
- A real two-player constructed game completed through the relay in
  `NetworkPlayIntegrationTest#testTrueNetworkTrafficThroughRelay`: lobby login,
  ready state, game start, nine delta-sync packets, and game finish all passed.
- The service has a standalone fat JAR, a loopback health endpoint, a hardened
  systemd unit example, and an Nginx TLS stream example.
- Production DNS, Nginx stream TLS termination, and the loopback relay service
  were deployed on 2026-08-25. A real client room-list request completed through
  `play.mtg-forge-kaorou.vip:443` with certificate and hostname verification.
- Desktop and Android clients now default to the public endpoint. They share the
  release-independent compatibility generation `forge-cn-net-1`, avoiding false
  incompatibility between the desktop display version and Android package
  version; bump this generation only for incompatible Forge network changes.

## Android control-frame incident and fix (2026-08-25)

The first Android production test could list rooms but timed out while creating
or joining one. The important distinction was connection lifetime: room listing
is one-shot, while registration and join use persistent TLS connections.

Diagnosis used stage logging on the Android worker thread, relay service logs,
and a metadata-only `tcpdump` comparison between public port 443 and loopback
port 36744. The capture proved all of the following for the same request:

- Android completed the TCP and TLS handshake and sent the registration frame;
- the relay registered the room and wrote the complete 85-byte response frame;
- Nginx sent the encrypted response to the device;
- the device acknowledged the TCP bytes but the blocking Java read did not
  return before its 35-second timeout.

Changing TLS 1.3 to TLS 1.2 and reducing the Nginx stream proxy buffer did not
change the failure. The actual compatibility fault was the use of
`InputStream.readNBytes()` in the Android control protocol. `RelayWire` now
allocates the validated frame/string length and calls
`DataInputStream.readFully()`, which has stable behavior across the supported
Android API range. The same change covers registration, join, reverse-tunnel,
and login setup rather than special-casing one screen.

After this change, both directions were verified on an Android 14 device:

- Android owner, desktop guest;
- desktop owner, Android guest.

Do not diagnose a room-list success as proof that persistent control messages
work. When this class of failure returns, log each protocol stage and compare
both sides of the TLS edge before changing timeouts or UI code.
