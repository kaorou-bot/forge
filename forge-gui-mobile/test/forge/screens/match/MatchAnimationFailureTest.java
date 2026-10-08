package forge.screens.match;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import forge.Forge;
import forge.Graphics;
import forge.MobileTestEnvironment;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gui.FThreads;
import org.mockito.MockedStatic;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MatchAnimationFailureTest {
    @BeforeClass
    public void initializeMobilePaths() throws Exception {
        MobileTestEnvironment.initialize();
    }

    @Test
    public void shaderFailureRestoresFramebufferAndGlState() {
        GL20 previousGl = Gdx.gl;
        ModelBatch previousBatch = Dice3D.sharedBatch;
        GL20 gl = mock(GL20.class);
        FrameBuffer buffer = mock(FrameBuffer.class);
        ModelBatch batch = mock(ModelBatch.class);
        ModelInstance model = mock(ModelInstance.class);
        Environment environment = new Environment();
        when(gl.glIsEnabled(GL20.GL_SCISSOR_TEST)).thenReturn(true);
        doThrow(new IllegalStateException("Missing shader")).when(batch).render(model, environment);
        Gdx.gl = gl;
        Dice3D.sharedBatch = batch;
        try {
            Assert.expectThrows(IllegalStateException.class,
                    () -> Dice3D.renderToBuffer(buffer, model, environment));
            verify(buffer).begin();
            verify(buffer).end();
            verify(batch).end();
            verify(gl).glEnable(GL20.GL_SCISSOR_TEST);
            verify(gl).glDisable(GL20.GL_DEPTH_TEST);
        } finally {
            Gdx.gl = previousGl;
            Dice3D.sharedBatch = previousBatch;
        }
    }

    @Test
    public void failedModelFlushAlsoRestoresFramebuffer() {
        GL20 previousGl = Gdx.gl;
        ModelBatch previousBatch = Dice3D.sharedBatch;
        GL20 gl = mock(GL20.class);
        FrameBuffer buffer = mock(FrameBuffer.class);
        ModelBatch batch = mock(ModelBatch.class);
        when(gl.glIsEnabled(GL20.GL_DEPTH_TEST)).thenReturn(true);
        doThrow(new IllegalStateException("Shader compilation failed")).when(batch).end();
        Gdx.gl = gl;
        Dice3D.sharedBatch = batch;
        try {
            Assert.expectThrows(IllegalStateException.class,
                    () -> Dice3D.renderToBuffer(buffer, mock(ModelInstance.class), new Environment()));
            verify(buffer).end();
            verify(gl, times(2)).glEnable(GL20.GL_DEPTH_TEST);
            verify(gl, times(2)).glDisable(GL20.GL_SCISSOR_TEST);
        } finally {
            Gdx.gl = previousGl;
            Dice3D.sharedBatch = previousBatch;
        }
    }

    @Test
    public void failedCoinRenderUsesFlatResultAndCompletesExactlyOnce() throws Exception {
        Application previousApp = Gdx.app;
        com.badlogic.gdx.Graphics previousGraphics = Gdx.graphics;
        Gdx.app = mock(Application.class);
        Gdx.graphics = mock(com.badlogic.gdx.Graphics.class);
        when(Gdx.graphics.getDeltaTime()).thenReturn(0.05f);
        Graphics graphics = mock(Graphics.class);
        SpriteBatch batch = mock(SpriteBatch.class);
        when(graphics.getBatch()).thenReturn(batch);
        when(batch.isDrawing()).thenReturn(true);
        FSkinFont font = mock(FSkinFont.class);
        when(font.getLineHeight()).thenReturn(24f);
        AtomicInteger completed = new AtomicInteger();
        try (MockedStatic<Forge> forge = mockStatic(Forge.class);
             MockedStatic<FSkinColor> colors = mockStatic(FSkinColor.class);
             MockedStatic<FSkinFont> fonts = mockStatic(FSkinFont.class);
             MockedStatic<FThreads> threads = mockStatic(FThreads.class)) {
            forge.when(Forge::getGraphics).thenReturn(graphics);
            fonts.when(() -> FSkinFont.get(anyInt())).thenReturn(font);
            threads.when(() -> FThreads.invokeInEdtLater(any(Runnable.class)))
                    .thenAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; });
            CoinFlipOverlay overlay = new CoinFlipOverlay(true, "先手已决定", false, completed::incrementAndGet);
            overlay.doLayout(1080, 2400);
            Coin3D coin = mock(Coin3D.class);
            doThrow(new IllegalStateException("Missing shader")).when(coin).render();
            setCoin(overlay, coin);
            for (int frame = 0; frame < 80; frame++) overlay.drawOverlay(graphics);
            verify(coin, times(1)).render();
            verify(coin, times(1)).dispose();
            verify(batch, times(1)).end();
            verify(batch, times(1)).begin();
            verify(graphics, atLeastOnce()).fillRect(any(com.badlogic.gdx.graphics.Color.class),
                    anyFloat(), anyFloat(), anyFloat(), anyFloat());
            overlay.hide();
            Assert.assertEquals(completed.get(), 1);
        } finally {
            Gdx.app = previousApp;
            Gdx.graphics = previousGraphics;
        }
    }

    @Test
    public void coinCleanupFailureCannotKeepGameThreadWaiting() throws Exception {
        Application previousApp = Gdx.app;
        Gdx.app = mock(Application.class);
        AtomicInteger completed = new AtomicInteger();
        try (MockedStatic<FSkinColor> colors = mockStatic(FSkinColor.class);
             MockedStatic<FThreads> threads = mockStatic(FThreads.class)) {
            CoinFlipOverlay overlay = new CoinFlipOverlay(false, "结果", true, completed::incrementAndGet);
            Coin3D coin = mock(Coin3D.class);
            doThrow(new IllegalStateException("Lost GL context")).when(coin).dispose();
            setCoin(overlay, coin);
            overlay.hide();
            overlay.hide();
            Assert.assertEquals(completed.get(), 1);
            verify(coin, times(1)).dispose();
        } finally {
            Gdx.app = previousApp;
        }
    }

    private static void setCoin(CoinFlipOverlay overlay, Coin3D coin) throws Exception {
        Field field = CoinFlipOverlay.class.getDeclaredField("coin");
        field.setAccessible(true);
        field.set(overlay, coin);
    }

    @Test
    public void failedDiceRenderReleasesCurrentAndQueuedGameWaits() throws Exception {
        Application previousApp = Gdx.app;
        Gdx.app = mock(Application.class);
        Graphics graphics = mock(Graphics.class);
        SpriteBatch batch = mock(SpriteBatch.class);
        when(graphics.getBatch()).thenReturn(batch);
        when(batch.isDrawing()).thenReturn(true);
        try (MockedStatic<Forge> forge = mockStatic(Forge.class)) {
            forge.when(Forge::getGraphics).thenReturn(graphics);
            DiceOverlay overlay = new DiceOverlay();
            Dice3D die = mock(Dice3D.class);
            doThrow(new IllegalStateException("Shader failure")).when(die).update(anyFloat());
            doThrow(new IllegalStateException("Cleanup failure")).when(die).dispose();
            Field dice = DiceOverlay.class.getDeclaredField("dice");
            dice.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.List<Dice3D> active = (java.util.List<Dice3D>) dice.get(overlay);
            active.add(die);
            Class<?> pendingType = Class.forName("forge.screens.match.DiceOverlay$Pending");
            java.lang.reflect.Constructor<?> constructor = pendingType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object current = constructor.newInstance(6, new int[] {3}, null);
            Object queued = constructor.newInstance(6, new int[] {4}, null);
            Field currentField = DiceOverlay.class.getDeclaredField("current");
            currentField.setAccessible(true);
            currentField.set(overlay, current);
            Field queue = DiceOverlay.class.getDeclaredField("queue");
            queue.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.Queue<Object> waiting = (java.util.Queue<Object>) queue.get(overlay);
            waiting.add(queued);
            overlay.update(0.05f);
            Field latch = pendingType.getDeclaredField("latch");
            latch.setAccessible(true);
            Assert.assertEquals(((java.util.concurrent.CountDownLatch) latch.get(current)).getCount(), 0L);
            Assert.assertEquals(((java.util.concurrent.CountDownLatch) latch.get(queued)).getCount(), 0L);
            Assert.assertFalse(overlay.isActive());
            verify(batch).begin();
        } finally {
            Gdx.app = previousApp;
        }
    }
}
