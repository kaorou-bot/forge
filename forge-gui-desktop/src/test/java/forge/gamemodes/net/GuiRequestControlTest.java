package forge.gamemodes.net;

import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.LoginEvent;
import forge.gamemodes.net.event.ReplyEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

public class GuiRequestControlTest {
    @Test
    public void loginAndNegotiatedCancellationSurviveActualWireCodec() {
        final EmbeddedChannel encoder = new EmbeddedChannel(new CompatibleObjectEncoder(null));
        final EmbeddedChannel decoder = new EmbeddedChannel(new CompatibleObjectDecoder(
                1024 * 1024, ClassResolvers.cacheDisabled(getClass().getClassLoader())));
        try {
            final LoginEvent login = new LoginEvent("player", 0, 0,
                    GuiRequestControl.loginVersion("2.0.15-test"), false);
            Assert.assertTrue(encoder.writeOutbound(login));
            Assert.assertTrue(decoder.writeInbound((Object) encoder.readOutbound()));
            final LoginEvent decodedLogin = decoder.readInbound();
            Assert.assertTrue(GuiRequestControl.supportsCancellation(decodedLogin.getVersion()));
            Assert.assertTrue(GuiRequestControl.supportsClock(decodedLogin.getVersion()));
            Assert.assertEquals(GuiRequestControl.baseVersion(decodedLogin.getVersion()), "2.0.15-test");
            Assert.assertEquals(decodedLogin.getUsername(), "player");
            for (ReplyEvent input : new ReplyEvent[] {GuiRequestControl.cancel(42),
                    GuiRequestControl.ready(42), GuiRequestControl.barrier(42),
                    GuiRequestControl.control(GuiRequestControl.HELLO)}) {
                Assert.assertTrue(encoder.writeOutbound(input));
                Assert.assertTrue(decoder.writeInbound((Object) encoder.readOutbound()));
                final ReplyEvent output = decoder.readInbound();
                Assert.assertEquals(output.getIndex(), GuiRequestControl.INDEX);
                Assert.assertEquals(output.getReply(), input.getReply());
            }
        } finally {
            encoder.finishAndReleaseAll();
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    public void controlsAreStrictAndConcurrentRequestsHaveUniqueNonnegativeIds() {
        Assert.assertTrue(GuiRequestControl.supportsCancellation(GuiRequestControl.loginVersion("test")));
        Assert.assertTrue(GuiRequestControl.supportsCancellation("test;forge-gui-cancel=1"));
        Assert.assertFalse(GuiRequestControl.supportsClock("test;forge-gui-cancel=1"));
        Assert.assertEquals(GuiRequestControl.baseVersion("test;forge-gui-cancel=1"), "test");
        Assert.assertTrue(GuiRequestControl.supportsClock("test;forge-gui-cancel=2"));
        Assert.assertEquals(GuiRequestControl.baseVersion("test;forge-gui-cancel=2"), "test");
        for (String invalid : new String[] {null, "", "test", ";forge-gui-cancel=1",
                ";forge-gui-cancel=2", "test;forge-gui-cancel=3", "test;forge-gui-cancel=1;other=1",
                "test;x=1;forge-gui-cancel=1", "test;forge-gui-cancel=2;other=1", "test;x=1;forge-gui-cancel=2"}) {
            Assert.assertFalse(GuiRequestControl.supportsCancellation(invalid));
            Assert.assertFalse(GuiRequestControl.supportsClock(invalid));
            Assert.assertEquals(GuiRequestControl.baseVersion(invalid), invalid);
        }
        for (Object invalid : new Object[] {null, "42", 42L, -1, java.util.List.of(42)}) {
            Assert.assertNull(GuiRequestControl.cancelledRequest(invalid));
        }
        Assert.assertEquals(GuiRequestControl.requestId("ready:42", "ready:"), Integer.valueOf(42));
        Assert.assertEquals(GuiRequestControl.requestId("barrier:42", "barrier:"), Integer.valueOf(42));
        for (Object invalid : new Object[] {null, 42, "ready:-1", "ready:+1", "ready:01", "ready:",
                "ready:2147483648", "ready:1 ", "barrier:42"}) {
            Assert.assertNull(GuiRequestControl.requestId(invalid, "ready:"));
        }
        final Set<Integer> ids = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 10000).parallel().forEach(i -> {
            final int id = new GuiGameEvent(ProtocolMethod.finishGame).getId();
            Assert.assertTrue(id >= 0);
            Assert.assertTrue(ids.add(id), "Concurrent requests collided");
        });
        Assert.assertEquals(ids.size(), 10000);
    }
}
