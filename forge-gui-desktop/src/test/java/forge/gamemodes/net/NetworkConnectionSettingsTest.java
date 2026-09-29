package forge.gamemodes.net;

import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.relay.client.RelayEndpoint;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.testng.Assert.*;

public class NetworkConnectionSettingsTest {
    @Test public void preferencesSurviveRestartAndModeChanges() throws Exception {
        Path file = Files.createTempFile("forge-connection-", ".preferences");
        try {
            ForgeNetPreferences prefs = new ForgeNetPreferences(file.toString());
            assertEquals(NetworkConnectionSettings.mode(prefs), NetworkConnectionSettings.Mode.SERVER);
            assertEquals(NetworkConnectionSettings.endpoint(prefs).address(), RelayEndpoint.COMMUNITY_ADDRESS);
            NetworkConnectionSettings.saveAddress(prefs, "relay.example.com:8443");
            NetworkConnectionSettings.saveMode(prefs, NetworkConnectionSettings.Mode.DIRECT);
            prefs = new ForgeNetPreferences(file.toString());
            assertEquals(NetworkConnectionSettings.mode(prefs), NetworkConnectionSettings.Mode.DIRECT);
            assertEquals(NetworkConnectionSettings.endpoint(prefs).address(), "tls://relay.example.com:8443");
            NetworkConnectionSettings.saveMode(prefs, NetworkConnectionSettings.Mode.SERVER);
            assertEquals(NetworkConnectionSettings.endpoint(prefs).port(), 8443);
            try {
                NetworkConnectionSettings.saveAddress(prefs, "https://wrong/path");
                fail("Must reject invalid address");
            } catch (IllegalArgumentException expected) { }
            assertEquals(NetworkConnectionSettings.endpoint(new ForgeNetPreferences(file.toString())).port(), 8443);
            NetworkConnectionSettings.saveAddress(prefs, "");
            assertEquals(NetworkConnectionSettings.endpoint(new ForgeNetPreferences(file.toString())).address(), RelayEndpoint.COMMUNITY_ADDRESS);
            prefs.setPref(FNetPref.NET_CONNECTION_MODE, "UNKNOWN");
            assertEquals(NetworkConnectionSettings.mode(prefs), NetworkConnectionSettings.Mode.SERVER);
        } finally { Files.deleteIfExists(file); }
    }
}
