package forge.assets;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import forge.MobileTestEnvironment;
import org.mockito.MockedStatic;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

public class FontHeightSelectionTest {
    @BeforeClass
    public void initializeMobilePaths() throws Exception {
        MobileTestEnvironment.initialize();
    }

    private FSkinFont font(float height) {
        FSkinFont font = mock(FSkinFont.class);
        when(font.getLineHeight()).thenReturn(height);
        return font;
    }

    @Test
    public void unavailableFontsReturnWithoutUnboundedGeneration() {
        FSkinFont pending = font(0);
        AtomicInteger requests = new AtomicInteger();
        try (MockedStatic<FSkinFont> fonts = mockStatic(FSkinFont.class, CALLS_REAL_METHODS)) {
            fonts.when(() -> FSkinFont._get(anyInt())).thenAnswer(invocation -> {
                Assert.assertTrue(requests.incrementAndGet() <= 80, "Unbounded font requests while fonts are pending");
                return pending;
            });
            Assert.assertSame(FSkinFont.forHeight(24), pending);
            Assert.assertTrue(requests.get() <= 73);
        }
    }

    @Test
    public void invalidMeasuredFontsAreSkipped() {
        FSkinFont invalid = font(Float.NaN);
        FSkinFont ready = font(20);
        AtomicInteger requests = new AtomicInteger();
        try (MockedStatic<FSkinFont> fonts = mockStatic(FSkinFont.class, CALLS_REAL_METHODS)) {
            fonts.when(() -> FSkinFont._get(anyInt())).thenAnswer(invocation -> {
                Assert.assertTrue(requests.incrementAndGet() <= 80, "Invalid metrics caused endless search");
                return (int) invocation.getArgument(0) == 16 ? ready : invalid;
            });
            Assert.assertSame(FSkinFont.forHeight(24), ready);
        }
    }

    @Test
    public void normalFitKeepsLargestReadyFontBelowHeight() {
        Map<Integer, FSkinFont> sizes = new HashMap<>();
        AtomicInteger requests = new AtomicInteger();
        try (MockedStatic<FSkinFont> fonts = mockStatic(FSkinFont.class, CALLS_REAL_METHODS)) {
            fonts.when(() -> FSkinFont._get(anyInt())).thenAnswer(invocation -> {
                Assert.assertTrue(requests.incrementAndGet() <= 80);
                int size = invocation.getArgument(0);
                return sizes.computeIfAbsent(size, key -> font(key * 2));
            });
            Assert.assertSame(FSkinFont.forHeight(24), sizes.get(12));
        }
    }

    @Test
    public void missingIntermediateFontDoesNotReplaceReadyChoice() {
        FSkinFont ready = font(22), pending = font(0), tooBig = font(26);
        try (MockedStatic<FSkinFont> fonts = mockStatic(FSkinFont.class, CALLS_REAL_METHODS)) {
            fonts.when(() -> FSkinFont._get(anyInt())).thenAnswer(invocation -> {
                int size = invocation.getArgument(0);
                return size == 11 ? ready : size >= 13 ? tooBig : pending;
            });
            Assert.assertSame(FSkinFont.forHeight(24), ready);
        }
    }

    @Test
    public void invalidRequestedHeightsUseBoundedFallback() {
        FSkinFont ready = font(16);
        AtomicInteger requests = new AtomicInteger();
        try (MockedStatic<FSkinFont> fonts = mockStatic(FSkinFont.class, CALLS_REAL_METHODS)) {
            fonts.when(() -> FSkinFont._get(anyInt())).thenAnswer(invocation -> {
                Assert.assertTrue(requests.incrementAndGet() <= 80, "Non-finite height caused endless search");
                return ready;
            });
            for (float height : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0, -1}) {
                Assert.assertSame(FSkinFont.forHeight(height), ready);
            }
            Assert.assertEquals(requests.get(), 5);
        }
    }

    @Test
    public void veryLargeHeightDoesNotCreateEveryIntermediateSize() {
        AtomicInteger requests = new AtomicInteger();
        try (MockedStatic<FSkinFont> fonts = mockStatic(FSkinFont.class, CALLS_REAL_METHODS)) {
            fonts.when(() -> FSkinFont._get(anyInt())).thenAnswer(invocation -> {
                Assert.assertTrue(requests.incrementAndGet() <= 80, "Huge height caused unbounded allocation");
                int size = invocation.getArgument(0);
                Assert.assertTrue(size <= 512);
                return font(size * 2);
            });
            Assert.assertNotNull(FSkinFont.forHeight(Float.MAX_VALUE));
        }
    }

    @Test
    public void scaledFontsRemainAvailableForLargeCards() {
        Map<Integer, FSkinFont> sizes = new HashMap<>();
        AtomicInteger requests = new AtomicInteger();
        try (MockedStatic<FSkinFont> fonts = mockStatic(FSkinFont.class, CALLS_REAL_METHODS)) {
            fonts.when(() -> FSkinFont._get(anyInt())).thenAnswer(invocation -> {
                Assert.assertTrue(requests.incrementAndGet() <= 80, "Scaled font searched linearly");
                int size = invocation.getArgument(0);
                return sizes.computeIfAbsent(size, key -> font(key * 2));
            });
            Assert.assertSame(FSkinFont.forHeight(240), sizes.get(120));
        }
    }
}
