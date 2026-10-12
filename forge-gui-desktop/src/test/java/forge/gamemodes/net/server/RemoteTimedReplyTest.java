package forge.gamemodes.net.server;

import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.GuiRequestControl;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.TimedGameDecisions;
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
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Real public transport path with a mocked formal Activity; broker/Game behavior is tested separately. */
@Test(timeOut = 10000)
public class RemoteTimedReplyTest {
    public void readyRequiresCurrentChannelAndKnownTimedRequest() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final AtomicInteger ready = new AtomicInteger();
        try (Wire wire = new Wire(); Wire stranger = new Wire();
             GuiRequestScope scope = countingScope(ready)) {
            final RemoteClient client = start(wire);
            final GuiGameEvent event = choice();
            final Future<Result> result = waiting(executor, client, event, scope);
            Assert.assertEquals(((GuiGameEvent) wire.next()).getId(), event.getId());
            client.acknowledgeReady(stranger.channel, event.getId());
            client.acknowledgeReady(wire.channel, event.getId() + 100000);
            Assert.assertEquals(ready.get(), 0);
            client.acknowledgeReady(wire.channel, event.getId());
            Assert.assertEquals(ready.get(), 1);
            Assert.assertTrue(client.completeTimedReply(wire.channel, event.getId(), "answer"));
            assertAnswer(result, "answer");
            client.acknowledgeReady(wire.channel, event.getId());
            Assert.assertEquals(ready.get(), 1, "Completed request must not become ready again");
            client.setTimedGame(false);
        } finally { shutdown(executor); }
    }

    public void inputBarrierIsAcknowledgedOnlyOnceByItsCurrentConnection() throws Exception {
        final AtomicInteger ready = new AtomicInteger();
        try (Wire wire = new Wire(); Wire stranger = new Wire(); GuiRequestScope scope = countingScope(ready);
             GuiRequestScope.WaitRegistration inputWait = scope.beginWait()) {
            final RemoteClient client = start(wire);
            client.watchInput(scope);
            final ReplyEvent barrier = (ReplyEvent) wire.next();
            final Integer id = GuiRequestControl.requestId(barrier.getReply(), "barrier:");
            Assert.assertNotNull(id);
            client.acknowledgeReady(stranger.channel, id);
            client.acknowledgeReady(wire.channel, id + 100000);
            Assert.assertEquals(ready.get(), 0);
            client.acknowledgeReady(wire.channel, id);
            client.acknowledgeReady(wire.channel, id);
            Assert.assertEquals(ready.get(), 1);
            client.setTimedGame(false);
        }
    }

    public void disconnectAndReconnectReplayTheLogicalWaitWithoutReturningNull() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Wire old = new Wire(); Wire replacement = new Wire(); GuiRequestScope scope = timedScope()) {
            final RemoteClient client = start(old);
            final GuiGameEvent event = choice();
            final Future<Result> result = waiting(executor, client, event, scope);
            Assert.assertEquals(((GuiGameEvent) old.next()).getId(), event.getId());
            client.disconnected();
            old.channel.close().sync();
            Assert.assertFalse(result.isDone(), "Disconnection is not a null answer");
            client.swapChannel(replacement.channel);
            Assert.assertFalse(result.isDone(), "Closing the legacy ReplyPool must not close the timed wait");
            client.negotiateClock(replacement.channel, GuiRequestControl.loginVersion("test"));
            Assert.assertEquals(((ReplyEvent) replacement.next()).getReply(), GuiRequestControl.HELLO);
            Assert.assertTrue(replacement.packets.isEmpty(), "Do not replay before resync completes");
            client.resyncComplete();
            Assert.assertEquals(((GuiGameEvent) replacement.next()).getId(), event.getId());
            client.completeTimedReply(old.channel, event.getId(), "stale");
            Assert.assertFalse(result.isDone(), "The previous channel answered the new connection's wait");
            Assert.assertTrue(client.completeTimedReply(replacement.channel, event.getId(), null));
            assertAnswer(result, null); // An explicit legitimate null remains distinct from disconnection.
            client.setTimedGame(false);
        } finally { shutdown(executor); }
    }

    public void timeoutCancelsOnlyItsOwnWaitAndLateReplyDoesNotCompleteAnother() throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Wire wire = new Wire(); GuiRequestScope first = timedScope();
             GuiRequestScope second = timedScope()) {
            final RemoteClient client = start(wire);
            final GuiGameEvent firstEvent = choice();
            final Future<Result> firstResult = waiting(executor, client, firstEvent, first);
            Assert.assertEquals(((GuiGameEvent) wire.next()).getId(), firstEvent.getId());
            final GuiGameEvent secondEvent = choice();
            final Future<Result> secondResult = waiting(executor, client, secondEvent, second);
            Assert.assertEquals(((GuiGameEvent) wire.next()).getId(), secondEvent.getId());
            first.cancel(GuiRequestScope.Reason.TIMED_OUT);
            assertCancelled(firstResult, GuiRequestScope.Reason.TIMED_OUT);
            wire.channel.runPendingTasks();
            Assert.assertEquals(((ReplyEvent) wire.next()).getReply(), firstEvent.getId());
            Assert.assertFalse(client.completeTimedReply(wire.channel, firstEvent.getId(), "late"));
            Assert.assertFalse(secondResult.isDone());
            client.completeTimedReply(wire.channel, secondEvent.getId(), "second");
            assertAnswer(secondResult, "second");
            Assert.assertTrue(wire.packets.isEmpty());
            client.setTimedGame(false);
        } finally { shutdown(executor); }
    }

    public void timeoutWhileDisconnectedEndsTheLogicalWaitWithoutReplayingIt() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Wire old = new Wire(); Wire replacement = new Wire(); GuiRequestScope scope = timedScope()) {
            final RemoteClient client = start(old);
            final GuiGameEvent event = choice();
            final Future<Result> result = waiting(executor, client, event, scope);
            old.next();
            client.disconnected();
            old.channel.close().sync();
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            assertCancelled(result, GuiRequestScope.Reason.TIMED_OUT);
            client.swapChannel(replacement.channel);
            client.negotiateClock(replacement.channel, GuiRequestControl.loginVersion("test"));
            replacement.next();
            client.resyncComplete();
            old.channel.runPendingTasks();
            replacement.channel.runPendingTasks();
            Assert.assertTrue(replacement.packets.isEmpty(), "Retired request was replayed after reconnect");
            client.setTimedGame(false);
        } finally { shutdown(executor); }
    }

    public void cancellationDuringEncodingNeverSendsTheStaleTimedPrompt() throws Exception {
        final CountDownLatch encoding = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger controlEncodes = new AtomicInteger();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Wire wire = new Wire(new CompatibleObjectEncoder(null) {
            @Override public ByteBuf encodeToBuf(final Serializable value, final ByteBufAllocator allocator) throws Exception {
                if (value instanceof GuiGameEvent) {
                    encoding.countDown();
                    if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Encoder barrier timed out");
                } else if (value instanceof ReplyEvent) controlEncodes.incrementAndGet();
                return super.encodeToBuf(value, allocator);
            }
        }); GuiRequestScope scope = timedScope()) {
            final RemoteClient client = start(wire);
            controlEncodes.set(0);
            final GuiGameEvent event = choice();
            final Future<Result> result = waiting(executor, client, event, scope);
            Assert.assertTrue(encoding.await(3, TimeUnit.SECONDS));
            scope.cancel(GuiRequestScope.Reason.TIMED_OUT);
            Assert.assertEquals(controlEncodes.get(), 0, "Timer must not encode control packets synchronously");
            release.countDown();
            assertCancelled(result, GuiRequestScope.Reason.TIMED_OUT);
            wire.channel.runPendingTasks();
            Assert.assertEquals(((ReplyEvent) wire.next()).getReply(), event.getId());
            Assert.assertTrue(wire.packets.isEmpty());
            client.setTimedGame(false);
        } finally { release.countDown(); shutdown(executor); }
    }

    public void endingTimedGameReleasesPendingWaitWithTypedClosure() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Wire wire = new Wire(); GuiRequestScope scope = timedScope()) {
            final RemoteClient client = start(wire);
            final GuiGameEvent event = choice();
            final Future<Result> result = waiting(executor, client, event, scope);
            wire.next();
            client.setTimedGame(false);
            assertCancelled(result, GuiRequestScope.Reason.GAME_CLOSED);
            Assert.assertFalse(client.completeTimedReply(wire.channel, event.getId(), "late game answer"));
        } finally { shutdown(executor); }
    }

    private static RemoteClient start(final Wire wire) throws Exception {
        final RemoteClient client = new RemoteClient(wire.channel);
        client.enableRequestCancellation(wire.channel);
        client.negotiateClock(wire.channel, GuiRequestControl.loginVersion("test"));
        Assert.assertEquals(((ReplyEvent) wire.next()).getReply(), GuiRequestControl.HELLO);
        client.setTimedGame(true);
        return client;
    }

    private static GuiRequestScope countingScope(final AtomicInteger ready) {
        final TimedGameDecisions.Activity activity = mock(TimedGameDecisions.Activity.class);
        final AtomicReference<GuiRequestScope.WaitRegistration> current = new AtomicReference<>();
        doAnswer(call -> {
            final GuiRequestScope.WaitRegistration token = new GuiRequestScope.WaitRegistration() {
                private final AtomicBoolean closed = new AtomicBoolean();
                @Override public void ready() { if (!closed.get()) ready.incrementAndGet(); }
                @Override public void close() { closed.compareAndSet(false, true); }
            };
            current.set(token);
            return token;
        }).when(activity).beginWait();
        doAnswer(call -> {
            final GuiRequestScope.WaitRegistration token = current.get();
            return (Runnable) () -> { if (token != null) token.ready(); };
        }).when(activity).captureReadiness();
        return new GuiRequestScope(activity);
    }

    private static GuiRequestScope timedScope() { return countingScope(new AtomicInteger()); }

    private static GuiGameEvent choice() {
        return new GuiGameEvent(ProtocolMethod.getChoices, "choose", 1, 1, List.of("a"), List.of(), null);
    }

    private record Result(Object answer, RuntimeException failure) { }

    private static Future<Result> waiting(final ExecutorService executor, final RemoteClient client,
                                          final GuiGameEvent event, final GuiRequestScope scope) {
        return executor.submit(() -> {
            final AtomicReference<Object> value = new AtomicReference<>();
            try {
                GuiRequestScope.bind(scope, () -> value.set(client.sendAndWait(event))).run();
                return new Result(value.get(), null);
            } catch (final RuntimeException failure) { return new Result(null, failure); }
        });
    }

    private static void assertAnswer(final Future<Result> future, final Object expected) throws Exception {
        final Result result = future.get(3, TimeUnit.SECONDS);
        Assert.assertNull(result.failure());
        Assert.assertEquals(result.answer(), expected);
    }

    private static void assertCancelled(final Future<Result> future, final GuiRequestScope.Reason reason) throws Exception {
        final Result result = future.get(3, TimeUnit.SECONDS);
        Assert.assertTrue(result.failure() instanceof GuiRequestScope.Cancelled, "Expected typed cancellation: " + result);
        Assert.assertEquals(((GuiRequestScope.Cancelled) result.failure()).reason(), reason);
    }

    private static void shutdown(final ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        Assert.assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "Test worker failed to terminate");
    }

    private static final class Wire implements AutoCloseable {
        private final BlockingQueue<Object> packets = new LinkedBlockingQueue<>();
        private final EmbeddedChannel channel;
        private final EmbeddedChannel decoder = new EmbeddedChannel(new CompatibleObjectDecoder(
                1024 * 1024, ClassResolvers.cacheDisabled(RemoteTimedReplyTest.class.getClassLoader())));
        private Wire() { this(new CompatibleObjectEncoder(null)); }
        private Wire(final CompatibleObjectEncoder encoder) {
            channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override public void write(final ChannelHandlerContext ctx, final Object value, final ChannelPromise promise) {
                    packets.add(value);
                    promise.setSuccess();
                }
            }, encoder);
        }
        private Object next() throws InterruptedException {
            final Object packet = packets.poll(3, TimeUnit.SECONDS);
            Assert.assertNotNull(packet, "Expected encoded packet");
            decoder.writeInbound(packet);
            final Object value = decoder.readInbound();
            Assert.assertNotNull(value);
            return value;
        }
        @Override public void close() {
            channel.finishAndReleaseAll();
            Object packet;
            while ((packet = packets.poll()) != null) ReferenceCountUtil.release(packet);
            decoder.finishAndReleaseAll();
        }
    }
}
