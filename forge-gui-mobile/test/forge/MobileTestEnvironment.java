package forge;

import java.nio.file.Files;
import java.nio.file.Path;

import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import org.mockito.Mockito;

public final class MobileTestEnvironment {
    private static boolean initialized;

    private MobileTestEnvironment() { }

    public static synchronized void initialize() throws Exception {
        if (initialized) {
            return;
        }
        // Keep every profile/font cache created by class initialization in the test output.
        Path assets = Files.createTempDirectory(Path.of("target"), "language-test-");
        IGuiBase gui = Mockito.mock(IGuiBase.class);
        Mockito.when(gui.getAssetsDir()).thenReturn(assets.toAbsolutePath() + "/");
        GuiBase.setInterface(gui);
        GuiBase.setIsAndroid(true);
        initialized = true;
    }
}
