package forge.relay;

import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Thread-safe in-memory room and pending-tunnel registry. */
final class RoomRegistry {
    static final int MAX_ROOMS = 1_000;
    static final int MAX_ROOMS_PER_OWNER_CONNECTION = 1;
    static final int MAX_PLAYERS = 8;
    static final int TUNNEL_HANDSHAKE_TIMEOUT_SECONDS = 30;

    private static final int PASSWORD_ITERATIONS = 120_000;
    private static final int PASSWORD_KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<Channel, String> owners = new ConcurrentHashMap<>();

    Room register(Channel ownerChannel, String compatibilityVersion, String roomName,
                  String ownerName, String format, String password, int maxPlayers)
            throws RegistryException {
        requireText(compatibilityVersion, "compatibility version", 128);
        requireText(roomName, "room name", 64);
        requireText(ownerName, "owner name", 64);
        if (format == null) {
            format = "";
        }
        if (format.length() > 64) {
            throw new RegistryException("INVALID_ROOM", "Format is too long");
        }
        if (maxPlayers < 2 || maxPlayers > MAX_PLAYERS) {
            throw new RegistryException("INVALID_ROOM", "Player limit must be between 2 and " + MAX_PLAYERS);
        }
        if (rooms.size() >= MAX_ROOMS) {
            throw new RegistryException("SERVER_FULL", "The lobby has reached its room limit");
        }
        if (owners.containsKey(ownerChannel)) {
            throw new RegistryException("ALREADY_HOSTING", "This connection already owns a room");
        }

        String roomId;
        do {
            roomId = randomToken(6);
        } while (rooms.containsKey(roomId));

        String ownerToken = randomToken(32);
        PasswordHash passwordHash = password == null || password.isEmpty() ? null : PasswordHash.create(password);
        Room room = new Room(roomId, ownerToken, compatibilityVersion, roomName,
                ownerName, format, passwordHash, maxPlayers, ownerChannel);
        rooms.put(roomId, room);
        owners.put(ownerChannel, roomId);
        return room;
    }

    List<RelayProtocol.RoomSnapshot> list(String compatibilityVersion) {
        List<RelayProtocol.RoomSnapshot> snapshots = new ArrayList<>();
        for (Room room : rooms.values()) {
            if (room.compatibilityVersion.equals(compatibilityVersion) && room.ownerChannel.isActive()) {
                snapshots.add(room.snapshot());
            }
        }
        snapshots.sort(Comparator.comparing(RelayProtocol.RoomSnapshot::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RelayProtocol.RoomSnapshot::roomId));
        return snapshots;
    }

    PendingTunnel requestJoin(Channel guestChannel, String compatibilityVersion,
                              String roomId, String password) throws RegistryException {
        Room room = rooms.get(roomId);
        if (room == null || !room.ownerChannel.isActive()) {
            throw new RegistryException("ROOM_NOT_FOUND", "The room is no longer available");
        }
        if (!room.compatibilityVersion.equals(compatibilityVersion)) {
            throw new RegistryException("INCOMPATIBLE_FORGE_VERSION", "The room requires a different Forge build");
        }
        if (!room.passwordMatches(password)) {
            throw new RegistryException("BAD_PASSWORD", "The room password is incorrect");
        }

        synchronized (room) {
            if (room.players() + room.pending.size() >= room.maxPlayers) {
                throw new RegistryException("ROOM_FULL", "The room is full");
            }
            String tunnelId = randomToken(16);
            PendingTunnel pending = new PendingTunnel(tunnelId, guestChannel, room);
            room.pending.put(tunnelId, pending);
            pending.timeout = guestChannel.eventLoop().schedule(
                    () -> expirePending(room, pending), TUNNEL_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return pending;
        }
    }

    TunnelPair acceptHostTunnel(Channel hostTunnel, String roomId, String ownerToken,
                                String tunnelId) throws RegistryException {
        Room room = rooms.get(roomId);
        if (room == null || !constantTimeEquals(room.ownerToken, ownerToken)) {
            throw new RegistryException("INVALID_OWNER_TOKEN", "Room owner credentials are invalid");
        }
        synchronized (room) {
            PendingTunnel pending = room.pending.remove(tunnelId);
            if (pending == null || !pending.guestChannel.isActive()) {
                throw new RegistryException("TUNNEL_NOT_FOUND", "The join request expired or was cancelled");
            }
            if (pending.timeout != null) {
                pending.timeout.cancel(false);
            }
            TunnelPair pair = new TunnelPair(room, hostTunnel, pending.guestChannel);
            room.activeTunnels.add(pair);
            return pair;
        }
    }

    void guestDisconnected(Channel guestChannel) {
        for (Room room : rooms.values()) {
            synchronized (room) {
                room.pending.values().removeIf(pending -> {
                    if (pending.guestChannel != guestChannel) {
                        return false;
                    }
                    if (pending.timeout != null) {
                        pending.timeout.cancel(false);
                    }
                    return true;
                });
            }
        }
    }

    void ownerDisconnected(Channel ownerChannel) {
        String roomId = owners.remove(ownerChannel);
        if (roomId == null) {
            return;
        }
        Room room = rooms.remove(roomId);
        if (room == null) {
            return;
        }
        synchronized (room) {
            for (PendingTunnel pending : room.pending.values()) {
                if (pending.timeout != null) {
                    pending.timeout.cancel(false);
                }
                pending.guestChannel.close();
            }
            room.pending.clear();
            for (TunnelPair pair : room.activeTunnels) {
                pair.close();
            }
            room.activeTunnels.clear();
        }
    }

    int size() {
        return rooms.size();
    }

    private void expirePending(Room room, PendingTunnel pending) {
        synchronized (room) {
            if (room.pending.remove(pending.tunnelId, pending)) {
                pending.guestChannel.writeAndFlush(
                        RelayProtocol.error("TUNNEL_TIMEOUT", "The room owner did not open a tunnel in time"))
                        .addListener(future -> pending.guestChannel.close());
            }
        }
    }

    private static void requireText(String value, String field, int maxChars) throws RegistryException {
        if (value == null || value.isBlank()) {
            throw new RegistryException("INVALID_ROOM", "Missing " + field);
        }
        if (value.length() > maxChars) {
            throw new RegistryException("INVALID_ROOM", field + " is too long");
        }
    }

    private static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return RelayProtocol.hexToken(value);
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
    }

    static final class Room {
        final String roomId;
        final String ownerToken;
        final String compatibilityVersion;
        final String name;
        final String ownerName;
        final String format;
        final PasswordHash passwordHash;
        final int maxPlayers;
        final Channel ownerChannel;
        final Map<String, PendingTunnel> pending = new ConcurrentHashMap<>();
        final Set<TunnelPair> activeTunnels = ConcurrentHashMap.newKeySet();

        Room(String roomId, String ownerToken, String compatibilityVersion, String name,
             String ownerName, String format, PasswordHash passwordHash, int maxPlayers,
             Channel ownerChannel) {
            this.roomId = roomId;
            this.ownerToken = ownerToken;
            this.compatibilityVersion = compatibilityVersion;
            this.name = name;
            this.ownerName = ownerName;
            this.format = format;
            this.passwordHash = passwordHash;
            this.maxPlayers = maxPlayers;
            this.ownerChannel = ownerChannel;
        }

        int players() {
            return 1 + activeTunnels.size();
        }

        boolean passwordMatches(String password) {
            return passwordHash == null ? password == null || password.isEmpty() : passwordHash.matches(password);
        }

        RelayProtocol.RoomSnapshot snapshot() {
            return new RelayProtocol.RoomSnapshot(roomId, name, ownerName, format,
                    compatibilityVersion, players(), maxPlayers, passwordHash != null);
        }
    }

    static final class PendingTunnel {
        final String tunnelId;
        final Channel guestChannel;
        final Room room;
        volatile ScheduledFuture<?> timeout;

        PendingTunnel(String tunnelId, Channel guestChannel, Room room) {
            this.tunnelId = tunnelId;
            this.guestChannel = guestChannel;
            this.room = room;
        }
    }

    static final class TunnelPair {
        final Room room;
        final Channel hostChannel;
        final Channel guestChannel;

        TunnelPair(Room room, Channel hostChannel, Channel guestChannel) {
            this.room = room;
            this.hostChannel = hostChannel;
            this.guestChannel = guestChannel;
        }

        void ended() {
            room.activeTunnels.remove(this);
        }

        void close() {
            hostChannel.close();
            guestChannel.close();
        }
    }

    static final class RegistryException extends Exception {
        private final String code;

        RegistryException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private record PasswordHash(byte[] salt, byte[] hash) {
        static PasswordHash create(String password) throws RegistryException {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            return new PasswordHash(salt, derive(password, salt));
        }

        boolean matches(String password) {
            if (password == null) {
                return false;
            }
            try {
                return MessageDigest.isEqual(hash, derive(password, salt));
            } catch (RegistryException ignored) {
                return false;
            }
        }

        private static byte[] derive(String password, byte[] salt) throws RegistryException {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt,
                    PASSWORD_ITERATIONS, PASSWORD_KEY_BITS);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
            } catch (GeneralSecurityException e) {
                throw new RegistryException("SERVER_ERROR", "Password hashing is unavailable");
            } finally {
                spec.clearPassword();
            }
        }
    }
}
