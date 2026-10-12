package forge.gamemodes.net.server;

import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.GuiRequestControl;
import forge.trackable.Tracker;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.util.IHasForgeLog;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public final class RemoteClient implements IToClient, IHasForgeLog {

    /** Special value indicating the client hasn't been assigned a slot yet. */
    public static final int UNASSIGNED_SLOT = -1;

    private record Connection(Channel channel, ReplyPool replies, boolean cancelSupported) { }
    private volatile Connection connection;
    private String username;
    private int index = UNASSIGNED_SLOT;
    private boolean libgdx;
    private volatile Tracker codecTracker;
    private volatile int codecConsumerId = -1;
    private final AtomicInteger sendErrors = new AtomicInteger(0);
    private RemoteClientGuiGame gui;
    private volatile boolean clockSupported;
    private volatile boolean timedGame;
    private volatile boolean connectionReady = true;
    private final java.util.concurrent.ConcurrentMap<Integer, TimedReply> timedReplies = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Integer, InputReadiness> readiness = new java.util.concurrent.ConcurrentHashMap<>();

    private record TimedReply(IdentifiableNetEvent event, forge.gui.GuiRequestScope scope,
                              forge.gui.GuiRequestScope.WaitRegistration waiting,
                              java.util.concurrent.CompletableFuture<Object> result) { }
    private record InputReadiness(forge.gui.GuiRequestScope scope, Runnable ready) { }

    public boolean supportsOperationClock() { return clockSupported; }
    public boolean isTimedGame() { return timedGame; }
    public void setTimedGame(final boolean enabled) {
        timedGame = enabled;
        if (!enabled) {
            for (final TimedReply reply : timedReplies.values()) reply.scope().cancel(forge.gui.GuiRequestScope.Reason.GAME_CLOSED);
            for (final var input : readiness.values()) input.scope().cancel(forge.gui.GuiRequestScope.Reason.GAME_CLOSED);
            readiness.clear();
        }
    }

    void negotiateClock(final Channel source, final String version) {
        if (!isCurrentChannel(source)) return;
        clockSupported = GuiRequestControl.supportsClock(version);
        if (clockSupported) send(source, GuiRequestControl.control(GuiRequestControl.HELLO));
    }

    void disconnected() { connectionReady = false; }

    void resyncComplete() {
        connectionReady = true;
        if (!timedGame) return;
        for (final TimedReply reply : timedReplies.values()) sendTimed(reply);
        for (final var entry : readiness.entrySet()) {
            if (!entry.getValue().scope().isCancelled()) send(GuiRequestControl.barrier(entry.getKey()));
        }
    }

    public void watchInput(final forge.gui.GuiRequestScope scope) {
        if (!timedGame || !clockSupported || scope.isCancelled()) return;
        final int id = forge.gamemodes.net.event.GuiGameEvent.allocateId();
        final InputReadiness input = new InputReadiness(scope, scope.captureReadiness());
        readiness.put(id, input);
        scope.onCancel(() -> readiness.remove(id, input));
        if (connectionReady) send(GuiRequestControl.barrier(id));
    }

    void acknowledgeReady(final Channel source, final int id) {
        if (!clockSupported || !timedGame || !isCurrentChannel(source)) return;
        final TimedReply reply = timedReplies.get(id);
        if (reply != null) {
            if (!reply.result().isDone()) reply.waiting().ready();
        } else {
            final InputReadiness input = readiness.remove(id);
            if (input != null) input.ready().run();
        }
    }

    boolean completeTimedReply(final Channel source, final int id, final Object value) {
        if (!isCurrentChannel(source)) return true;
        final TimedReply reply = timedReplies.get(id);
        if (reply == null) return false;
        if (reply.result().isDone()) return true;
        try {
            reply.waiting().close();
            reply.scope().checkActive();
            reply.result().complete(value);
        } catch (final RuntimeException failure) { reply.result().completeExceptionally(failure); }
        return true;
    }

    private void sendTimed(final TimedReply reply) {
        if (!connectionReady || reply.result().isDone() || reply.scope().isCancelled()) return;
        final Connection target = connection;
        final ByteBuf encoded = encodeOnCallingThread(target.channel(), reply.event());
        if (encoded == null) {
            reply.result().completeExceptionally(new IllegalStateException("Unable to encode timed player choice"));
            return;
        }
        synchronized (reply) {
            if (!isCurrentChannel(target.channel()) || !connectionReady || reply.result().isDone()) {
                encoded.release();
                return;
            }
            target.channel().writeAndFlush(encoded).addListener(f -> {
                // Disconnect is not an answer. Replay the logical wait after resynchronization.
                if (!f.isSuccess() && isCurrentChannel(target.channel())) target.channel().close();
            });
        }
    }

    private Object sendTimedAndWait(final IdentifiableNetEvent event, final forge.gui.GuiRequestScope scope) {
        scope.checkActive();
        final forge.gui.GuiRequestScope.WaitRegistration wait = scope.beginWait();
        final TimedReply reply = new TimedReply(event, scope, wait, new java.util.concurrent.CompletableFuture<>());
        if (timedReplies.putIfAbsent(event.getId(), reply) != null) {
            wait.close();
            throw new IllegalStateException("Duplicate timed choice");
        }
        final var cancellation = scope.onCancel(() -> {
            synchronized (reply) {
              if (reply.result().completeExceptionally(scope.cancellation())) {
                final Channel target = connection.channel();
                target.eventLoop().execute(() -> {
                    if (isCurrentChannel(target) && target.isActive()) send(target, GuiRequestControl.cancel(event.getId()));
                });
              }
            }
        });
        try (cancellation; wait) {
            sendTimed(reply);
            final Object answer = reply.result().get();
            scope.checkActive();
            return answer;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted timed choice", interrupted);
        } catch (final java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException(failure.getCause());
        } finally { timedReplies.remove(event.getId(), reply); }
    }

    // Package-private: SaturationLoggingHandler reads/resets these on writability transitions
    volatile long saturationStartMs = 0L;
    final AtomicInteger sendsDuringSaturation = new AtomicInteger(0);

    private void recordSendIfSaturated(final Channel ch) {
        if (!ch.isWritable()) {
            sendsDuringSaturation.incrementAndGet();
        }
    }

    public RemoteClient(final Channel channel) {
        connection = new Connection(channel, new ReplyPool(), false);
    }

    /**
     * Swap the underlying channel for a reconnecting client.
     * Updates the channel, creates a fresh ReplyPool, and re-applies the codec
     * tracker to the new channel's pipeline so IdRef resolution keeps working.
     */
    public synchronized void swapChannel(final Channel newChannel) {
        connectionReady = false;
        clockSupported = false;
        final Connection previous = connection;
        connection = new Connection(newChannel, new ReplyPool(), false);
        synchronized (previous.replies()) { previous.replies().close(); }
        applyCodecTracker(newChannel);
    }

    public boolean isCurrentChannel(final Channel channel) { return connection.channel() == channel; }

    /** Capability is authenticated by the handler's channel, never by a claimed player ID. */
    void enableRequestCancellation(final Channel channel) {
        synchronized (this) {
            final Connection current = connection;
            if (current.channel() == channel) {
                connection = new Connection(channel, current.replies(), true);
            }
        }
    }

    /** Existing AFK cancellation only; this does not declare a player lost. */
    void cancelPendingReplies() {
        final Connection current = connection;
        synchronized (current.replies()) {
            final java.util.List<Integer> cancelled = current.replies().cancelAll();
            if (!current.cancelSupported() || !current.channel().isActive()) return;
            for (final int id : cancelled) send(current.channel(), GuiRequestControl.cancel(id));
        }
    }

    /**
     * Check if this client has been assigned a valid lobby slot.
     * @return true if the client has a valid slot (index >= 0)
     */
    public boolean hasValidSlot() {
        return index >= 0;
    }

    /** Remote peer address, for admission limits and logging. */
    public SocketAddress getRemoteAddress() {
        final Channel ch = connection.channel();
        return ch == null ? null : ch.remoteAddress();
    }

    /** Encodes synchronously on the caller's thread. Returns null on failure (logged). */
    private ByteBuf encodeOnCallingThread(final Channel ch, final NetEvent event) {
        final CompatibleObjectEncoder encoder = ch.pipeline().get(CompatibleObjectEncoder.class);
        if (encoder == null) {
            netLog.error("No encoder in pipeline for {} (event: {})", username, event);
            sendErrors.incrementAndGet();
            return null;
        }
        try {
            return encoder.encodeToBuf(event, ch.alloc());
        } catch (Exception e) {
            sendErrors.incrementAndGet();
            netLog.error(e, "Network encode error for {} (event: {})", username, event);
            return null;
        }
    }

    @Override
    public void send(final NetEvent event) {
        send(connection.channel(), event);
    }

    private void send(final Channel ch, final NetEvent event) {
        recordSendIfSaturated(ch);
        final ByteBuf encoded = encodeOnCallingThread(ch, event);
        if (encoded == null) return;
        ch.writeAndFlush(encoded).addListener(f -> {
            if (!f.isSuccess()) {
                sendErrors.incrementAndGet();
                Throwable c = f.cause();
                if (c != null) {
                    netLog.error(c, "Network send error for {} (event: {})", username, event);
                } else {
                    netLog.error("Network send error for {} (event: {}, cause: {})",
                            username, event, f.isCancelled() ? "cancelled" : "no cause");
                }
            }
        });
    }

    @Override
    public void write(final NetEvent event) {
        final Channel ch = connection.channel();
        recordSendIfSaturated(ch);
        final ByteBuf encoded = encodeOnCallingThread(ch, event);
        if (encoded == null) return;
        ch.write(encoded).addListener(f -> {
            if (!f.isSuccess()) {
                sendErrors.incrementAndGet();
                Throwable c = f.cause();
                if (c != null) {
                    netLog.error(c, "Network write error for {} (event: {})", username, event);
                } else {
                    netLog.error("Network write error for {} (event: {}, cause: {})",
                            username, event, f.isCancelled() ? "cancelled" : "no cause");
                }
            }
        });
    }

    @Override
    public Object sendAndWait(final IdentifiableNetEvent event) {
        final forge.gui.GuiRequestScope activeScope = forge.gui.GuiRequestScope.current();
        if (timedGame && activeScope != null && activeScope.activity() instanceof forge.gamemodes.net.TimedGameDecisions.Activity) {
            return sendTimedAndWait(event, activeScope);
        }
        // Encoding, asynchronous failure, and waiting must all belong to one connection.
        final Connection current = connection;
        final ReplyPool replies = current.replies();
        final forge.gui.GuiRequestScope scope = forge.gui.GuiRequestScope.current();
        final forge.gui.GuiRequestScope.WaitRegistration wait = scope == null
                ? forge.gui.GuiRequestScope.WaitRegistration.NONE : scope.beginWait();
        replies.initialize(event.getId(), () -> {
            if (scope != null) {
                wait.close();
                scope.checkActive();
            }
        });
        final forge.gui.GuiRequestScope.Registration cancellation = scope == null
                ? forge.gui.GuiRequestScope.Registration.NONE : scope.onCancel(() -> {
                    synchronized (replies) {
                        if (replies.fail(event.getId(), scope.cancellation())
                                && current.cancelSupported() && current.channel().isActive()) {
                            // The timer only completes a future. Encoding/transport cleanup must
                            // not block it, and must never switch to a replacement connection.
                            current.channel().eventLoop().execute(() -> {
                                if (current.channel().isActive()) {
                                    send(current.channel(), GuiRequestControl.cancel(event.getId()));
                                }
                            });
                        }
                    }
                });
        try (cancellation; wait) {
            return sendAndWaitOnConnection(event, current);
        }
    }

    private Object sendAndWaitOnConnection(final IdentifiableNetEvent event, final Connection current) {
        final ReplyPool replies = current.replies();
        final Channel ch = current.channel();
        recordSendIfSaturated(ch);
        final ByteBuf encoded = encodeOnCallingThread(ch, event);
        if (encoded == null) {
            replies.complete(event.getId(), null);
            return replies.get(event.getId());
        }
        synchronized (replies) {
            // A cancellation can win while encoding. Never send a stale prompt after its cancel.
            if (!replies.isPending(event.getId())) {
                encoded.release();
            } else {
                ch.writeAndFlush(encoded).addListener(f -> {
                    if (!f.isSuccess()) {
                        sendErrors.incrementAndGet();
                        Throwable c = f.cause();
                        if (c != null) {
                            netLog.error(c, "sendAndWait write failed for {} (event: {})", username, event);
                        } else {
                            netLog.error("sendAndWait write failed for {} (event: {}, cause: {})",
                                    username, event, f.isCancelled() ? "cancelled" : "no cause");
                        }
                        replies.complete(event.getId(), null);
                    }
                });
            }
        }
        return replies.get(event.getId());
    }

    public String getUsername() {
        return username;
    }
    public void setUsername(final String username) {
        this.username = username;
    }

    public int getIndex() {
        return index;
    }
    public void setIndex(final int index) {
        this.index = index;
    }

    public boolean isLibgdx() {
        return libgdx;
    }
    public void setLibgdx(final boolean libgdx) {
        this.libgdx = libgdx;
    }

    public RemoteClientGuiGame getGui() {
        return gui;
    }
    void setGui(final RemoteClientGuiGame gui) {
        this.gui = gui;
    }

    /**
     * Set the tracker and per-client consumerId on the channel's encoder and
     * decoder. Called when the game starts (before any client protocol
     * messages arrive). Cached so that {@link #swapChannel} can re-apply
     * after a reconnect. The consumerId is the {@code DeltaSyncManager} id
     * for this client; the encoder uses it to gate IdRef substitution to
     * objects this client has actually been told about.
     */
    public void setCodecTracker(Tracker tracker, int consumerId) {
        // Skip no-op rebinds: setGameView fires on every view push, the tracker changes per game.
        if (tracker == codecTracker && consumerId == codecConsumerId) {
            return;
        }
        this.codecTracker = tracker;
        this.codecConsumerId = consumerId;
        applyCodecTracker(connection.channel());
    }

    private void applyCodecTracker(Channel ch) {
        if (codecTracker == null || ch == null) {
            return;
        }
        // Swap on the event loop so it lands between decoded frames: a message the
        // client sent against the previous game must not resolve against the new one.
        if (!ch.eventLoop().inEventLoop()) {
            ch.eventLoop().execute(() -> applyCodecTracker(ch));
            return;
        }
        CompatibleObjectEncoder encoder = ch.pipeline().get(CompatibleObjectEncoder.class);
        if (encoder != null) {
            encoder.setTracker(codecTracker);
            encoder.setConsumerId(codecConsumerId);
        }
        CompatibleObjectDecoder decoder = ch.pipeline().get(CompatibleObjectDecoder.class);
        if (decoder != null) {
            decoder.setTracker(codecTracker);
        }
    }

    public int getSendErrorCount() {
        return sendErrors.get();
    }

    ReplyPool getReplyPool() {
        return connection.replies();
    }

    ReplyPool getReplyPool(final Channel source) {
        final Connection current = connection;
        return current.channel() == source ? current.replies() : null;
    }
}
