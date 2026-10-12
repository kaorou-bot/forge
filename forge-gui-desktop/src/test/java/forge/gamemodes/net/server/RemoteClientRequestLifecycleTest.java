package forge.gamemodes.net.server;

import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.GuiRequestControl;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.ReplyEvent;
import forge.gui.GuiRequestScope;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.util.ReferenceCountUtil;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.Serializable;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class RemoteClientRequestLifecycleTest {
    @Test(timeOut = 10000)
    public void scopeTimeoutDuringEncodingFailsTypedWaitWithoutSendingStalePrompt() throws Exception {
        final CountDownLatch encoding = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final AtomicInteger controlEncodes = new AtomicInteger();
        final Wire wire = new Wire(new CompatibleObjectEncoder(null) {
            @Override public ByteBuf encodeToBuf(final Serializable msg, final ByteBufAllocator alloc) throws Exception {
                if (msg instanceof GuiGameEvent) {
                    encoding.countDown();
                    if (!resume.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Encoder barrier timed out");
                } else if (msg instanceof ReplyEvent) controlEncodes.incrementAndGet();
                return super.encodeToBuf(msg, alloc);
            }
        });
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try (wire; GuiRequestScope scope = new GuiRequestScope()) {
            final RemoteClient client = new RemoteClient(wire.channel);
            client.enableRequestCancellation(wire.channel);
            final GuiGameEvent request = choice();
            final Future<WaitResult> waiting = waitScoped(executor, client, request, scope);
            Assert.assertTrue(encoding.await(3, TimeUnit.SECONDS));
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            Assert.assertEquals(controlEncodes.get(), 0, "Timer encoded transport cleanup synchronously");
            resume.countDown();
            assertTimedOut(waiting.get(3, TimeUnit.SECONDS));
            wire.channel.runPendingTasks();
            final ReplyEvent cancel = (ReplyEvent) wire.next();
            Assert.assertEquals(cancel.getIndex(), GuiRequestControl.INDEX);
            Assert.assertEquals(cancel.getReply(), request.getId());
            Assert.assertTrue(wire.packets.isEmpty(), "Cancelled request was sent after its cancellation");
        } finally { resume.countDown(); executor.shutdownNow(); }
    }

    @Test(timeOut = 10000)
    public void twoSentWaitsCancelOnlyTheirOwnIdAndNormalNullRemainsAnAnswer() throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Wire wire = new Wire(new CompatibleObjectEncoder(null));
             GuiRequestScope firstScope = new GuiRequestScope(); GuiRequestScope secondScope = new GuiRequestScope()) {
            final RemoteClient client = new RemoteClient(wire.channel);
            client.enableRequestCancellation(wire.channel);
            final GuiGameEvent first = choice();
            final Future<WaitResult> firstWait = waitScoped(executor, client, first, firstScope);
            Assert.assertEquals(((GuiGameEvent) wire.next()).getId(), first.getId());
            final GuiGameEvent second = choice();
            final Future<WaitResult> secondWait = waitScoped(executor, client, second, secondScope);
            Assert.assertEquals(((GuiGameEvent) wire.next()).getId(), second.getId());
            firstScope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            assertTimedOut(firstWait.get(3, TimeUnit.SECONDS));
            Assert.assertTrue(client.getReplyPool().isPending(second.getId()));
            client.getReplyPool().complete(first.getId(), "late");
            client.getReplyPool().complete(second.getId(), null);
            final WaitResult normal = secondWait.get(3, TimeUnit.SECONDS);
            Assert.assertNull(normal.failure());
            Assert.assertNull(normal.value());
            wire.channel.runPendingTasks();
            Assert.assertEquals(((ReplyEvent) wire.next()).getReply(), first.getId());
            Assert.assertTrue(wire.packets.isEmpty());
        } finally { executor.shutdownNow(); }
    }

    @Test(timeOut = 10000)
    public void queuedScopeCancellationStaysOnOldConnectionAfterSwap() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Wire old = new Wire(new CompatibleObjectEncoder(null));
             Wire replacement = new Wire(new CompatibleObjectEncoder(null)); GuiRequestScope scope = new GuiRequestScope()) {
            final RemoteClient client = new RemoteClient(old.channel);
            client.enableRequestCancellation(old.channel);
            final GuiGameEvent request = choice();
            final Future<WaitResult> waiting = waitScoped(executor, client, request, scope);
            old.next();
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            client.swapChannel(replacement.channel);
            client.getReplyPool().initialize(request.getId());
            old.channel.runPendingTasks();
            replacement.channel.runPendingTasks();
            assertTimedOut(waiting.get(3, TimeUnit.SECONDS));
            Assert.assertEquals(((ReplyEvent) old.next()).getReply(), request.getId());
            Assert.assertTrue(replacement.packets.isEmpty());
            Assert.assertTrue(client.getReplyPool().isPending(request.getId()));
            client.getReplyPool().complete(request.getId(), "replacement answer");
            Assert.assertEquals(client.getReplyPool().get(request.getId()), "replacement answer");
        } finally { executor.shutdownNow(); }
    }

    @Test
    public void acceptedReplyWinsBeforeScopeCancellationAndDoesNotSendCancel() {
        final AtomicReference<RemoteClient> client = new AtomicReference<>();
        final GuiGameEvent request = choice();
        try (GuiRequestScope scope = new GuiRequestScope()) {
            final EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
                    ReferenceCountUtil.release(msg);
                    client.get().getReplyPool().complete(request.getId(), "accepted");
                    scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
                    promise.setSuccess();
                }
            }, new CompatibleObjectEncoder(null));
            try {
                client.set(new RemoteClient(channel));
                client.get().enableRequestCancellation(channel);
                final AtomicReference<Object> result = new AtomicReference<>();
                GuiRequestScope.bind(scope, () -> result.set(client.get().sendAndWait(request))).run();
                Assert.assertEquals(result.get(), "accepted");
                channel.runPendingTasks();
                Assert.assertNull(channel.readOutbound());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    private record WaitResult(Object value, Throwable failure) { }
    private static GuiGameEvent choice() {
        return new GuiGameEvent(ProtocolMethod.getChoices, "choose", 1, 1,
                java.util.List.of("a"), java.util.List.of(), null);
    }
    private static Future<WaitResult> waitScoped(final ExecutorService executor, final RemoteClient client,
                                               final GuiGameEvent request, final GuiRequestScope scope) {
        return executor.submit(() -> {
            final AtomicReference<Object> value = new AtomicReference<>();
            try {
                GuiRequestScope.bind(scope, () -> value.set(client.sendAndWait(request))).run();
                return new WaitResult(value.get(), null);
            } catch (final RuntimeException failure) { return new WaitResult(null, failure); }
        });
    }
    private static void assertTimedOut(final WaitResult result) {
        Assert.assertTrue(result.failure() instanceof GuiRequestScope.Cancelled, "Expected typed cancellation: " + result);
        Assert.assertEquals(((GuiRequestScope.Cancelled) result.failure()).reason(), GuiRequestScope.Reason.TIMED_OUT);
    }
    private static final class Wire implements AutoCloseable {
        private final BlockingQueue<Object> packets = new LinkedBlockingQueue<>();
        private final EmbeddedChannel channel;
        private final EmbeddedChannel decoder = new EmbeddedChannel(new CompatibleObjectDecoder(
                1024 * 1024, ClassResolvers.cacheDisabled(RemoteClientRequestLifecycleTest.class.getClassLoader())));
        private Wire(final CompatibleObjectEncoder encoder) {
            channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
                    packets.add(msg);
                    promise.setSuccess();
                }
            }, encoder);
        }
        private Object next() throws InterruptedException {
            final Object bytes = packets.poll(3, TimeUnit.SECONDS);
            Assert.assertNotNull(bytes, "Expected wire frame");
            decoder.writeInbound(bytes);
            final Object message = decoder.readInbound();
            Assert.assertNotNull(message);
            return message;
        }
        @Override public void close() {
            channel.finishAndReleaseAll();
            Object packet;
            while ((packet = packets.poll()) != null) ReferenceCountUtil.release(packet);
            decoder.finishAndReleaseAll();
        }
    }

    @Test(timeOut = 5000)
    public void acceptedReplySurvivesCancellationBeforeSendAndWaitStartsGet() {
        final AtomicReference<RemoteClient> remote = new AtomicReference<>();
        final AtomicReference<GuiGameEvent> request = new AtomicReference<>();
        final EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(final ChannelHandlerContext ctx, final Object msg,
                                        final ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                remote.get().getReplyPool().complete(request.get().getId(), java.util.List.of("accepted"));
                remote.get().cancelPendingReplies();
                promise.setSuccess();
            }
        }, new CompatibleObjectEncoder(null));
        try {
            remote.set(new RemoteClient(channel));
            remote.get().enableRequestCancellation(channel);
            request.set(new GuiGameEvent(ProtocolMethod.getChoices,
                    "choose", 1, 1, java.util.List.of("accepted"), java.util.List.of(), null));
            Assert.assertEquals(remote.get().sendAndWait(request.get()), java.util.List.of("accepted"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void cancellationIsNegotiatedPerConnectionAndTargetsOnlyPendingIds() {
        final EmbeddedChannel first = new EmbeddedChannel(new CompatibleObjectEncoder(null));
        final EmbeddedChannel second = new EmbeddedChannel(new CompatibleObjectEncoder(null));
        final EmbeddedChannel decoder = new EmbeddedChannel(new CompatibleObjectDecoder(
                1024 * 1024, ClassResolvers.cacheDisabled(getClass().getClassLoader())));
        try {
            final RemoteClient client = new RemoteClient(first);
            client.getReplyPool().initialize(10);
            client.cancelPendingReplies();
            Assert.assertNull(first.readOutbound(), "Legacy client must not receive controls");
            client.enableRequestCancellation(second); // Not this peer's channel.
            client.getReplyPool().initialize(11);
            client.cancelPendingReplies();
            Assert.assertNull(first.readOutbound());
            client.enableRequestCancellation(first);
            client.getReplyPool().initialize(12);
            client.getReplyPool().initialize(13);
            client.getReplyPool().initialize(14);
            client.getReplyPool().complete(14, "done");
            client.cancelPendingReplies();
            final Set<Integer> ids = new HashSet<>();
            for (int i = 0; i < 2; i++) {
                Assert.assertTrue(decoder.writeInbound((Object) first.readOutbound()));
                final ReplyEvent event = decoder.readInbound();
                Assert.assertEquals(event.getIndex(), GuiRequestControl.INDEX);
                ids.add(GuiRequestControl.cancelledRequest(event.getReply()));
            }
            Assert.assertEquals(ids, Set.of(12, 13));
            Assert.assertNull(first.readOutbound());
            final ReplyPool previous = client.getReplyPool();
            client.swapChannel(second);
            Assert.assertNull(client.getReplyPool(first));
            previous.initialize(15);
            Assert.assertNull(previous.get(15), "Retired pool cannot accept late waits");
            client.getReplyPool().initialize(16);
            client.cancelPendingReplies();
            Assert.assertNull(second.readOutbound(), "Capability must be renegotiated after reconnect");
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
            decoder.finishAndReleaseAll();
        }
    }

    @Test(timeOut = 10000)
    public void reconnectDuringEncodingSuppressesRetiredPromptAndReleasesWait() throws Exception {
        final CountDownLatch encoding = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final CountDownLatch written = new CountDownLatch(1);
        final AtomicReference<ChannelPromise> failure = new AtomicReference<>();
        final EmbeddedChannel old = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(final ChannelHandlerContext ctx, final Object msg,
                                        final ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                failure.set(promise);
                written.countDown();
            }
        }, new CompatibleObjectEncoder(null) {
            @Override public ByteBuf encodeToBuf(final Serializable msg, final ByteBufAllocator alloc) throws Exception {
                encoding.countDown();
                if (!resume.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release encoder");
                return super.encodeToBuf(msg, alloc);
            }
        });
        final EmbeddedChannel replacement = new EmbeddedChannel(new CompatibleObjectEncoder(null));
        final var executor = Executors.newSingleThreadExecutor();
        try {
            final RemoteClient client = new RemoteClient(old);
            final GuiGameEvent request = new GuiGameEvent(ProtocolMethod.getChoices,
                    "choose", 1, 1, java.util.List.of("a"), java.util.List.of(), null);
            final var waiting = executor.submit(() -> client.sendAndWait(request));
            Assert.assertTrue(encoding.await(2, TimeUnit.SECONDS));
            client.swapChannel(replacement);
            // Deliberately reuse a number to detect an old callback touching the new pool.
            client.getReplyPool().initialize(request.getId());
            resume.countDown();
            Assert.assertNull(waiting.get(2, TimeUnit.SECONDS));
            Assert.assertEquals(written.getCount(), 1L, "Retired prompt must not be sent");
            client.getReplyPool().complete(request.getId(), "new answer");
            Assert.assertEquals(client.getReplyPool().get(request.getId()), "new answer");
            Assert.assertNull(replacement.readOutbound(), "Old invocation was sent on the new channel");
        } finally {
            resume.countDown();
            executor.shutdownNow();
            old.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }

    @Test(timeOut = 10000)
    public void delayedOldWriteFailureCannotAnswerNewConnectionRequest() throws Exception {
        final AtomicReference<ChannelPromise> failure = new AtomicReference<>();
        final CountDownLatch written = new CountDownLatch(1);
        final EmbeddedChannel old = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(final ChannelHandlerContext ctx, final Object msg,
                                        final ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                failure.set(promise);
                written.countDown();
            }
        }, new CompatibleObjectEncoder(null));
        final EmbeddedChannel replacement = new EmbeddedChannel(new CompatibleObjectEncoder(null));
        final var executor = Executors.newSingleThreadExecutor();
        try {
            final RemoteClient client = new RemoteClient(old);
            final GuiGameEvent request = new GuiGameEvent(ProtocolMethod.getChoices,
                    "choose", 1, 1, java.util.List.of("a"), java.util.List.of(), null);
            final var waiting = executor.submit(() -> client.sendAndWait(request));
            Assert.assertTrue(written.await(2, TimeUnit.SECONDS));
            client.swapChannel(replacement);
            client.getReplyPool().initialize(request.getId());
            failure.get().setFailure(new IllegalStateException("Injected old transport failure"));
            Assert.assertNull(waiting.get(2, TimeUnit.SECONDS));
            client.getReplyPool().complete(request.getId(), "new answer");
            Assert.assertEquals(client.getReplyPool().get(request.getId()), "new answer");
        } finally {
            executor.shutdownNow();
            old.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
    }
}
