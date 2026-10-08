package forge;

import java.lang.reflect.Field;

import forge.assets.Assets;
import forge.screens.FScreen;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;

public class ForgeDisposeLifecycleTest {
    @BeforeClass
    public void initializeMobilePaths() throws Exception {
        MobileTestEnvironment.initialize();
    }

    @Test
    public void backendSecondDisposalDoesNotCloseScreensOrNativeAssetsAgain() throws Exception {
        boolean oldDisposed = Forge.isDisposed;
        boolean oldClosing = Forge.lifecycleClosing;
        Assets oldAssets = Assets.instance;
        Field screenField = Forge.class.getDeclaredField("currentScreen");
        screenField.setAccessible(true);
        Object oldScreen = screenField.get(null);
        FScreen screen = mock(FScreen.class);
        Assets assets = mock(Assets.class);
        try {
            Forge.isDisposed = true;
            Forge.lifecycleClosing = false;
            Assets.instance = assets;
            screenField.set(null, screen);
            Forge app = mock(Forge.class, CALLS_REAL_METHODS);
            app.dispose();
            app.triggerDispose();
            verifyNoInteractions(screen, assets);
            Assert.assertSame(screenField.get(null), screen);
            Assert.assertFalse(Forge.lifecycleClosing);
        } finally {
            Forge.isDisposed = oldDisposed;
            Forge.lifecycleClosing = oldClosing;
            Assets.instance = oldAssets;
            screenField.set(null, oldScreen);
        }
    }
}
