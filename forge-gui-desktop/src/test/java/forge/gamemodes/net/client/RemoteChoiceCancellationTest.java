package forge.gamemodes.net.client;

import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.GuiRequestControl;
import forge.gamemodes.net.event.ReplyEvent;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gui.FThreads;
import forge.gui.GuiRequestScope;
import forge.gui.RequestTestGui;
import forge.gui.interfaces.IGuiGame;
import forge.util.WaitCallback;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Test(singleThreaded = true)
public class RemoteChoiceCancellationTest {
    @DataProvider(name = "endings")
    public Object[][] endings() { return new Object[][] { {"disconnect"}, {"finish"}, {"cancel"} }; }

    @Test(dataProvider = "endings", timeOut = 10000)
    public void actualClientDispatchCancelsChoiceWithoutSendingFakeAnswer(final String ending) throws Exception {
        try (RequestTestGui dispatcher = new RequestTestGui(); GuiRequestScope unrelated = new GuiRequestScope()) {
            final AtomicInteger closed = new AtomicInteger();
            final AtomicReference<WaitCallback<List<String>>> callback = new AtomicReference<>();
            final CountDownLatch exited = new CountDownLatch(1);
            final IGuiGame gui = (IGuiGame) Proxy.newProxyInstance(IGuiGame.class.getClassLoader(),
                    new Class<?>[] { IGuiGame.class }, (proxy, method, args) -> {
                        if (!method.getName().equals("getChoices")) return null;
                        final WaitCallback<List<String>> choice = new WaitCallback<>() {
                            @Override public void run() {
                                final GuiRequestScope scope = GuiRequestScope.current();
                                Assert.assertNotNull(scope);
                                scope.onCancel(() -> FThreads.invokeInEdtLater(closed::incrementAndGet));
                            }
                        };
                        callback.set(choice);
                        try { return choice.invokeAndWait(); }
                        finally { exited.countDown(); }
                    });
            final EmbeddedChannel channel = new EmbeddedChannel();
            channel.pipeline().addLast("test-context", new ChannelInboundHandlerAdapter());
            try (FGameClient client = new FGameClient("test", gui, "127.0.0.1", 1)) {
                final var field = FGameClient.class.getDeclaredField("channel");
                field.setAccessible(true);
                field.set(client, channel);
                final var handler = new GameClientHandler(client);
                final var ctx = channel.pipeline().context("test-context");
                final GuiGameEvent request = new GuiGameEvent(ProtocolMethod.getChoices,
                        "choose", 1, 1, List.of("a", "b"), List.of(), null);
                handler.channelRead(ctx, request);
                dispatcher.next().run();
                handler.channelRead(ctx, GuiRequestControl.cancel(Integer.MAX_VALUE - 1));
                handler.channelRead(ctx, new ReplyEvent(GuiRequestControl.INDEX, "bad"));
                Assert.assertEquals(exited.getCount(), 1L, "Unrelated/malformed cancellation must do nothing");
                if (ending.equals("finish")) handler.channelRead(ctx, new GuiGameEvent(ProtocolMethod.finishGame));
                else if (ending.equals("cancel")) handler.channelRead(ctx, GuiRequestControl.cancel(request.getId()));
                else handler.channelInactive(ctx);
                Assert.assertTrue(exited.await(2, TimeUnit.SECONDS), "Real client invocation is still waiting");
                dispatcher.next().run(); // Only this request's registered UI cleanup.
                if (ending.equals("finish")) dispatcher.next().run(); // The actual finishGame GUI call.
                callback.get().accept(List.of("late"));
                Assert.assertEquals(closed.get(), 1);
                Assert.assertFalse(unrelated.isCancelled());
                Assert.assertNull(channel.readOutbound(), "Cancellation must not become a null/late ReplyEvent");
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test(timeOut = 10000)
    public void queuedCancellationSkipsUiAndOldChannelCannotCancelNewChoice() throws Exception {
        try (RequestTestGui dispatcher = new RequestTestGui()) {
            final AtomicInteger opened = new AtomicInteger();
            final CountDownLatch exited = new CountDownLatch(1);
            final AtomicReference<WaitCallback<List<String>>> callback = new AtomicReference<>();
            final IGuiGame gui = (IGuiGame) Proxy.newProxyInstance(IGuiGame.class.getClassLoader(),
                    new Class<?>[] { IGuiGame.class }, (proxy, method, args) -> {
                        if (!method.getName().equals("getChoices")) return null;
                        final WaitCallback<List<String>> choice = new WaitCallback<>() {
                            @Override public void run() { opened.incrementAndGet(); }
                        };
                        callback.set(choice);
                        try { return choice.invokeAndWait(); }
                        finally { exited.countDown(); }
                    });
            final EmbeddedChannel old = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
            final EmbeddedChannel active = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
            try (FGameClient client = new FGameClient("test", gui, "127.0.0.1", 1)) {
                final var field = FGameClient.class.getDeclaredField("channel");
                field.setAccessible(true);
                field.set(client, active);
                final var handler = new GameClientHandler(client);
                final var ctx = active.pipeline().firstContext();
                final GuiGameEvent request = new GuiGameEvent(ProtocolMethod.getChoices,
                        "choose", 1, 1, List.of("a"), List.of(), null);
                handler.channelRead(ctx, request);
                final Runnable queuedUi = dispatcher.next();
                handler.channelRead(old.pipeline().firstContext(), GuiRequestControl.cancel(request.getId()));
                Assert.assertEquals(exited.getCount(), 1L);
                handler.channelRead(ctx, GuiRequestControl.cancel(request.getId()));
                handler.channelRead(ctx, GuiRequestControl.cancel(request.getId())); // Idempotent.
                Assert.assertTrue(exited.await(2, TimeUnit.SECONDS));
                queuedUi.run();
                callback.get().accept(List.of("late"));
                Assert.assertEquals(opened.get(), 0);
                Assert.assertNull(active.readOutbound());
                Assert.assertNull(old.readOutbound());
            } finally {
                old.finishAndReleaseAll();
                active.finishAndReleaseAll();
            }
        }
    }
}
