package forge.util;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.testng.Assert.*;

public class AndroidStreamCompatibilityTest {
    @Test
    public void preservesOrderDuplicatesAndNulls() {
        final List<String> result = Stream.of("second", null, "first", "second")
                .collect(StreamUtil.toImmutableList());
        assertEquals(result, Arrays.asList("second", null, "first", "second"));
        assertThrows(UnsupportedOperationException.class, () -> result.add("other"));
        assertThrows(UnsupportedOperationException.class, () -> result.set(0, "other"));
        assertThrows(UnsupportedOperationException.class, () -> result.remove(0));
    }

    @Test
    public void createsIndependentSnapshot() {
        final List<String> source = new ArrayList<>(Arrays.asList("a", "b"));
        final List<String> result = source.stream().collect(StreamUtil.toImmutableList());
        source.clear();
        assertEquals(result, Arrays.asList("a", "b"));
    }

    @Test
    public void emptyResultIsUnmodifiable() {
        final List<String> result = Stream.<String>empty().collect(StreamUtil.toImmutableList());
        assertTrue(result.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.add("other"));
    }

    @Test
    public void parallelCollectionKeepsEncounterOrder() {
        final List<Integer> result = IntStream.range(0, 1000).parallel().boxed()
                .collect(StreamUtil.toImmutableList());
        assertEquals(result.size(), 1000);
        for (int i = 0; i < result.size(); i++) {
            assertEquals(result.get(i).intValue(), i);
        }
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }
}
