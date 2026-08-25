package forge.relay;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central room registry and opaque TCP relay.
 *
 * <p>This first vertical slice intentionally has no TLS implementation. It is
 * safe only on localhost or behind an authenticated TLS edge during development.
 */
public final class RelayServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RelayServer.class.getName());
    private static final int HANDSHAKE_TIMEOUT_SECONDS = 90;
    private static final int MAX_CONNECTIONS = 4_096;
    private static final int MAX_CONNECTIONS_PER_IP = 64;
    private static final int LOW_WATER_MARK = 64 * 1024;
    private static final int HIGH_WATER_MARK = 1024 * 1024;

    private final String bindAddress;
    private final int requestedPort;
    private final RoomRegistry registry = new RoomRegistry();
    private final IpRateLimiter rateLimiter = new IpRateLimiter();
    private final AtomicInteger connections = new AtomicInteger();
    private final java.util.concurrent.ConcurrentHashMap<java.net.InetAddress, AtomicInteger>
            connectionsByAddress = new java.util.concurrent.ConcurrentHashMap<>();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public RelayServer(String bindAddress, int port) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
    }

    public synchronized int start() throws InterruptedException {
        if (serverChannel != null) {
            return port();
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(LOW_WATER_MARK, HIGH_WATER_MARK))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast("readTimeout",
                                new ReadTimeoutHandler(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                        channel.pipeline().addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                                RelayProtocol.MAX_CONTROL_FRAME, 0, 4, 0, 4));
                        channel.pipeline().addLast("framePrepender", new LengthFieldPrepender(4));
                        channel.pipeline().addLast("handshake", new HandshakeHandler());
                    }
                });

        serverChannel = bootstrap.bind(new InetSocketAddress(bindAddress, requestedPort)).sync().channel();
        LOG.info(() -> "Forge lobby relay listening on " + bindAddress + ":" + port());
        return port();
    }

    public int port() {
        if (serverChannel == null) {
            return requestedPort;
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    int roomCount() {
        return registry.size();
    }

    int connectionCount() {
        return connections.get();
    }

    @Override
    public synchronized void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
    }

    private final class HandshakeHandler extends ChannelInboundHandlerAdapter {
        private ConnectionRole role = ConnectionRole.UNKNOWN;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            java.net.InetAddress address = remote.getAddress();
            AtomicInteger addressConnections = connectionsByAddress.computeIfAbsent(
                    address, ignored -> new AtomicInteger());
            ctx.channel().attr(CONNECTION_ADDRESS).set(address);
            int totalConnections = connections.incrementAndGet();
            int perAddressConnections = addressConnections.incrementAndGet();
            if (totalConnections > MAX_CONNECTIONS
                    || perAddressConnections > MAX_CONNECTIONS_PER_IP) {
                releaseConnection(ctx.channel());
                ctx.close();
                return;
            }
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf frame)) {
                ReferenceCountUtil.release(msg);
                fail(ctx, "MALFORMED_REQUEST", "Expected a framed control message");
                return;
            }
            try {
                if (!frame.isReadable()) {
                    throw new RelayProtocol.ProtocolException("MALFORMED_REQUEST", "Empty control message");
                }
                byte type = frame.readByte();
                if (role == ConnectionRole.OWNER_CONTROL) {
                    handleOwnerMessage(ctx, type, frame);
                } else if (role == ConnectionRole.UNKNOWN) {
                    handleInitialMessage(ctx, type, frame);
                } else {
                    throw new RelayProtocol.ProtocolException("UNEXPECTED_MESSAGE", "Connection is waiting for a tunnel");
                }
            } catch (RelayProtocol.ProtocolException e) {
                fail(ctx, e.code(), e.getMessage());
            } catch (RoomRegistry.RegistryException e) {
                fail(ctx, e.code(), e.getMessage());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Relay control handler failed", e);
                fail(ctx, "SERVER_ERROR", "Unable to process request");
            } finally {
                frame.release();
            }
        }

        private void handleInitialMessage(ChannelHandlerContext ctx, byte type, ByteBuf frame)
                throws RelayProtocol.ProtocolException, RoomRegistry.RegistryException {
            RelayProtocol.requireProtocol(frame);
            switch (type) {
                case RelayProtocol.REGISTER_ROOM -> {
                    requireRate(ctx, IpRateLimiter.Action.REGISTER);
                    registerRoom(ctx, frame);
                }
                case RelayProtocol.LIST_ROOMS -> {
                    requireRate(ctx, IpRateLimiter.Action.LIST);
                    listRooms(ctx, frame);
                }
                case RelayProtocol.JOIN_ROOM -> {
                    requireRate(ctx, IpRateLimiter.Action.JOIN);
                    joinRoom(ctx, frame);
                }
                case RelayProtocol.HOST_TUNNEL -> acceptHostTunnel(ctx, frame);
                default -> throw new RelayProtocol.ProtocolException(
                        "UNEXPECTED_MESSAGE", "Unsupported initial message type " + type);
            }
        }

        private void handleOwnerMessage(ChannelHandlerContext ctx, byte type, ByteBuf frame)
                throws RelayProtocol.ProtocolException {
            if (type != RelayProtocol.HEARTBEAT || frame.isReadable()) {
                throw new RelayProtocol.ProtocolException("UNEXPECTED_MESSAGE", "Only heartbeat is allowed here");
            }
            ctx.writeAndFlush(RelayProtocol.message(RelayProtocol.HEARTBEAT, out -> { }));
        }

        private void registerRoom(ChannelHandlerContext ctx, ByteBuf frame)
                throws RelayProtocol.ProtocolException, RoomRegistry.RegistryException {
            String compatibilityVersion = RelayProtocol.readString(frame, "compatibility version");
            String roomName = RelayProtocol.readString(frame, "room name");
            String ownerName = RelayProtocol.readString(frame, "owner name");
            String format = RelayProtocol.readString(frame, "format");
            String password = RelayProtocol.readString(frame, "password");
            int maxPlayers = RelayProtocol.readInt(frame, "maximum players");
            requireConsumed(frame);

            RoomRegistry.Room room = registry.register(ctx.channel(), compatibilityVersion,
                    roomName, ownerName, format, password, maxPlayers);
            role = ConnectionRole.OWNER_CONTROL;
            LOG.info(() -> "event=room_registered room=" + room.roomId
                    + " remote=" + remoteAddress(ctx));
            ctx.writeAndFlush(RelayProtocol.registered(room.roomId, room.ownerToken));
        }

        private void listRooms(ChannelHandlerContext ctx, ByteBuf frame)
                throws RelayProtocol.ProtocolException {
            String compatibilityVersion = RelayProtocol.readString(frame, "compatibility version");
            requireConsumed(frame);
            role = ConnectionRole.ONE_SHOT;
            ctx.writeAndFlush(RelayProtocol.roomList(registry.list(compatibilityVersion)))
                    .addListener(future -> ctx.close());
        }

        private void joinRoom(ChannelHandlerContext ctx, ByteBuf frame)
                throws RelayProtocol.ProtocolException, RoomRegistry.RegistryException {
            String compatibilityVersion = RelayProtocol.readString(frame, "compatibility version");
            String roomId = RelayProtocol.readString(frame, "room id");
            String password = RelayProtocol.readString(frame, "password");
            requireConsumed(frame);

            RoomRegistry.PendingTunnel pending = registry.requestJoin(
                    ctx.channel(), compatibilityVersion, roomId, password);
            role = ConnectionRole.PENDING_GUEST;
            LOG.info(() -> "event=join_requested room=" + roomId
                    + " remote=" + remoteAddress(ctx));
            ctx.writeAndFlush(RelayProtocol.joinPending(pending.tunnelId));
            pending.room.ownerChannel.writeAndFlush(
                    RelayProtocol.openTunnel(pending.room.roomId, pending.tunnelId));
        }

        private void acceptHostTunnel(ChannelHandlerContext ctx, ByteBuf frame)
                throws RelayProtocol.ProtocolException, RoomRegistry.RegistryException {
            String roomId = RelayProtocol.readString(frame, "room id");
            String ownerToken = RelayProtocol.readString(frame, "owner token");
            String tunnelId = RelayProtocol.readString(frame, "tunnel id");
            requireConsumed(frame);

            role = ConnectionRole.HOST_TUNNEL;
            RoomRegistry.TunnelPair pair = registry.acceptHostTunnel(
                    ctx.channel(), roomId, ownerToken, tunnelId);
            LOG.info(() -> "event=tunnel_paired room=" + roomId
                    + " remote=" + remoteAddress(ctx));
            activatePair(pair);
        }

        private void requireRate(ChannelHandlerContext ctx, IpRateLimiter.Action action)
                throws RelayProtocol.ProtocolException {
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            if (!rateLimiter.allow(remote.getAddress(), action)) {
                LOG.warning(() -> "event=rate_limited action=" + action
                        + " remote=" + remote.getAddress().getHostAddress());
                throw new RelayProtocol.ProtocolException(
                        "RATE_LIMITED", "Too many lobby requests; please wait and try again");
            }
        }

        private String remoteAddress(ChannelHandlerContext ctx) {
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            return remote.getAddress().getHostAddress();
        }

        private void requireConsumed(ByteBuf frame) throws RelayProtocol.ProtocolException {
            if (frame.isReadable()) {
                throw new RelayProtocol.ProtocolException("MALFORMED_REQUEST", "Unexpected trailing data");
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            releaseConnection(ctx.channel());
            if (role == ConnectionRole.OWNER_CONTROL) {
                LOG.info(() -> "event=owner_disconnected remote=" + remoteAddress(ctx));
                registry.ownerDisconnected(ctx.channel());
            } else if (role == ConnectionRole.PENDING_GUEST) {
                registry.guestDisconnected(ctx.channel());
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.log(Level.FINE, "Relay connection closed after error", cause);
            ctx.close();
        }

        private void fail(ChannelHandlerContext ctx, String code, String message) {
            ctx.writeAndFlush(RelayProtocol.error(code, message)).addListener(future -> ctx.close());
        }
    }

    private void activatePair(RoomRegistry.TunnelPair pair) {
        AtomicInteger remaining = new AtomicInteger(2);
        AtomicBoolean failed = new AtomicBoolean();
        switchToRaw(pair.hostChannel, pair.guestChannel, pair,
                () -> rawSideReady(pair, remaining, failed));
        switchToRaw(pair.guestChannel, pair.hostChannel, pair,
                () -> rawSideReady(pair, remaining, failed));
    }

    private void rawSideReady(RoomRegistry.TunnelPair pair, AtomicInteger remaining,
                              AtomicBoolean failed) {
        if (remaining.decrementAndGet() != 0) {
            return;
        }
        if (failed.get() || !pair.hostChannel.isActive() || !pair.guestChannel.isActive()) {
            pair.ended();
            pair.close();
            return;
        }

        // Both inbound pipelines must be raw before either peer is told it may
        // send game bytes. Sending TUNNEL_READY first creates a race where a fast
        // peer's first game frame can still hit LengthFieldBasedFrameDecoder.
        ChannelFuture hostReady = pair.hostChannel.writeAndFlush(framed(RelayProtocol.tunnelReady()));
        ChannelFuture guestReady = pair.guestChannel.writeAndFlush(framed(RelayProtocol.tunnelReady()));
        hostReady.addListener(future -> {
            if (!future.isSuccess()) {
                pair.close();
            }
        });
        guestReady.addListener(future -> {
            if (!future.isSuccess()) {
                pair.close();
            }
        });
    }

    private void switchToRaw(Channel source, Channel destination, RoomRegistry.TunnelPair pair,
                             Runnable completion) {
        source.eventLoop().execute(() -> {
            if (!source.isActive()) {
                completion.run();
                return;
            }
            if (source.pipeline().get("readTimeout") != null) {
                source.pipeline().remove("readTimeout");
            }
            if (source.pipeline().get("frameDecoder") != null) {
                source.pipeline().remove("frameDecoder");
            }
            if (source.pipeline().get("framePrepender") != null) {
                source.pipeline().remove("framePrepender");
            }
            if (source.pipeline().get("handshake") != null) {
                source.pipeline().replace("handshake", "relay", new RelayForwardHandler(destination, pair));
            }
            completion.run();
        });
    }

    private static ByteBuf framed(ByteBuf payload) {
        ByteBuf framed = Unpooled.buffer(Integer.BYTES + payload.readableBytes());
        framed.writeInt(payload.readableBytes());
        framed.writeBytes(payload);
        payload.release();
        return framed;
    }

    private final class RelayForwardHandler extends ChannelInboundHandlerAdapter {
        private final Channel destination;
        private final RoomRegistry.TunnelPair pair;
        private final AtomicBoolean ended = new AtomicBoolean();

        RelayForwardHandler(Channel destination, RoomRegistry.TunnelPair pair) {
            this.destination = destination;
            this.pair = pair;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!destination.isActive()) {
                ReferenceCountUtil.release(msg);
                ctx.close();
                return;
            }
            ChannelFuture write = destination.writeAndFlush(msg);
            if (!destination.isWritable()) {
                ctx.channel().config().setAutoRead(false);
            }
            write.addListener(future -> {
                if (!future.isSuccess()) {
                    ctx.close();
                }
            });
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            if (ctx.channel().isWritable() && destination.isActive()) {
                destination.config().setAutoRead(true);
            }
            super.channelWritabilityChanged(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            releaseConnection(ctx.channel());
            if (ended.compareAndSet(false, true)) {
                pair.ended();
                destination.close();
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private static final io.netty.util.AttributeKey<java.net.InetAddress> CONNECTION_ADDRESS =
            io.netty.util.AttributeKey.valueOf("relayConnectionAddress");

    private void releaseConnection(Channel channel) {
        java.net.InetAddress address = channel.attr(CONNECTION_ADDRESS).getAndSet(null);
        if (address == null) {
            return;
        }
        connections.decrementAndGet();
        connectionsByAddress.computeIfPresent(address, (ignored, count) ->
                count.decrementAndGet() <= 0 ? null : count);
    }

    private enum ConnectionRole {
        UNKNOWN,
        OWNER_CONTROL,
        PENDING_GUEST,
        HOST_TUNNEL,
        ONE_SHOT
    }
}
