package forge.gamemodes.net;

import forge.game.GameView;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.server.FServerManager;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.gui.util.SOptionPane;
import forge.util.Localizer;

/** A compact, shareable report without addresses, tokens, player names, decks or card contents. */
public final class NetworkDiagnostics {
    private NetworkDiagnostics() { }

    public static String report(final FGameClient client, final GameView game) {
        String result = "Forge " + GuiBase.getInterface().getCurrentVersion() + "\n"
                + (client == null ? "role=host\ntransport="
                    + (FServerManager.getInstance().isRelayHosting() ? "relay" : "direct")
                    : "role=guest\n" + client.connectionDiagnostics());
        if (game != null) {
            result += "\nturn=" + game.getTurn() + "\nphase=" + game.getPhase()
                    + "\nplayers=" + (game.getPlayers() == null ? 0 : game.getPlayers().size());
        }
        return result;
    }

    public static void show(final FGameClient client, final GameView game) {
        final String report = report(client, game);
        FThreads.invokeInBackgroundThread(() -> {
            final Localizer loc = Localizer.getInstance();
            final int action = SOptionPane.showOptionDialog(
                    loc.getMessage("lblNetworkDiagnosticsHint") + "\n\n" + report,
                    loc.getMessage("lblNetworkDiagnostics"), null,
                    java.util.List.of(loc.getMessage("lblNetworkCopyReport"), loc.getMessage("lblOK")), 1);
            if (action == 0) GuiBase.getInterface().copyToClipboard(report);
        });
    }
}
