package forge.gamemodes.net;

import forge.gui.util.SOptionPane;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.relay.client.RelayEndpoint;
import forge.util.Localizer;

/** Shared desktop/mobile connection preferences, separate from in-room game format. */
public final class NetworkConnectionSettings {
    private NetworkConnectionSettings() { }

    public enum Mode {
        SERVER("lblConnectionServer"), DIRECT("lblConnectionDirect");
        private final String key;
        Mode(String key) { this.key = key; }
        @Override public String toString() { return Localizer.getInstance().getMessage(key); }
    }

    public static Mode mode(ForgeNetPreferences prefs) {
        try { return Mode.valueOf(prefs.getPref(FNetPref.NET_CONNECTION_MODE)); }
        catch (IllegalArgumentException e) { return Mode.SERVER; }
    }

    public static void saveMode(ForgeNetPreferences prefs, Mode mode) {
        prefs.setPref(FNetPref.NET_CONNECTION_MODE, mode.name());
        prefs.save();
    }

    public static RelayEndpoint endpoint(ForgeNetPreferences prefs) {
        return RelayEndpoint.parse(prefs.getPref(FNetPref.NET_RELAY_ADDRESS));
    }

    public static void saveAddress(ForgeNetPreferences prefs, String address) {
        // Validate before changing/saving; malformed input leaves the previous endpoint intact.
        String normalized = RelayEndpoint.parse(address).address();
        prefs.setPref(FNetPref.NET_RELAY_ADDRESS, normalized);
        prefs.save();
    }

    /** Call on the platform's dialog-capable thread (background on mobile). */
    public static void editServer(ForgeNetPreferences prefs) {
        Localizer text = Localizer.getInstance();
        String input = prefs.getPref(FNetPref.NET_RELAY_ADDRESS);
        while (true) {
            input = SOptionPane.showInputDialog(text.getMessage("lblRelayAddressPrompt"),
                    text.getMessage("lblRelayAddress"), null, input);
            if (input == null) return;
            try {
                saveAddress(prefs, input);
                return;
            } catch (IllegalArgumentException e) {
                SOptionPane.showErrorDialog(text.getMessage("lblRelayAddressInvalid"));
            }
        }
    }
}
