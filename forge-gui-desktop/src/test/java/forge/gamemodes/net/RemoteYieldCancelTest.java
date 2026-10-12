package forge.gamemodes.net;

import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.gamemodes.match.YieldController;
import forge.gamemodes.match.YieldUpdate;
import forge.gamemodes.net.client.IToServer;
import forge.gamemodes.net.client.NetGameController;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

public class RemoteYieldCancelTest {
    @Test
    public void cancelUsesWireCommandsAndClearsRemoteLocalCache() {
        forge.net.TestUtils.ensureFModelInitialized();
        final List<GuiGameEvent> sent = new ArrayList<>();
        NetGameController controller = new NetGameController(new IToServer() {
            @Override public void send(NetEvent event) { sent.add((GuiGameEvent) event); }
            @Override public Object sendAndWait(IdentifiableNetEvent event) { return null; }
        });
        PlayerView player = new PlayerView(1, null);
        YieldController yields = controller.getYieldController();
        yields.setMarker(player, PhaseType.MAIN2, false);
        YieldController.cancelActiveYields(controller, player);
        Assert.assertFalse(yields.isYieldActive());
        Assert.assertEquals(sent.size(), 3);
        Assert.assertEquals(sent.get(0).getMethod(), ProtocolMethod.sendYieldUpdate);
        Assert.assertTrue(sent.get(0).getObjects()[0] instanceof YieldUpdate.ClearMarker);
        yields.setAutoPassUntilEndOfTurn(true);
        YieldController.cancelActiveYields(controller, player);
        Assert.assertFalse(yields.isYieldActive());
        yields.setAutoPassUntilStackEmpty(true, true);
        YieldController.cancelActiveYields(controller, player);
        Assert.assertFalse(yields.isYieldActive());
    }
}
