package forge.gamemodes.net;

import forge.gamemodes.net.client.FGameClient;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;

public class NetworkDiagnosticsTest {
    @Test
    public void reportUsesPlatformVersionAndOmitsPersonalConnectionData() {
        forge.net.TestUtils.ensureFModelInitialized();
        final IGuiBase original = GuiBase.getInterface();
        final IGuiBase platform = (IGuiBase) Proxy.newProxyInstance(
                IGuiBase.class.getClassLoader(), new Class<?>[] {IGuiBase.class},
                (proxy, method, args) -> method.getName().equals("getCurrentVersion")
                        ? "android-platform-test-version" : method.invoke(original, args));
        GuiBase.setInterface(platform);
        try (FGameClient client = new FGameClient("private-player", null, "private-host", 12345)) {
            final String report = NetworkDiagnostics.report(client, null);
            Assert.assertTrue(report.startsWith("Forge android-platform-test-version\n"));
            Assert.assertTrue(report.contains("role=guest"));
            Assert.assertFalse(report.contains("private-player"));
            Assert.assertFalse(report.contains("private-host"));
            Assert.assertFalse(report.contains("12345"));
        } finally {
            GuiBase.setInterface(original);
        }
    }
}
