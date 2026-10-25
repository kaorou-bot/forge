package forge.adventure.player;

import forge.Forge;
import forge.MobileTestEnvironment;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.util.Localizer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class AdventureEmptyDeckCompatibilityTest {
    private static void field(AdventurePlayer player, String name, Object value) throws Exception {
        final Field field = AdventurePlayer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    @DataProvider(name = "slots")
    public Object[][] slots() {
        return new Object[][] {{"空甲板", false, true}, {"空套牌", false, true},
                {"空甲板", true, false}, {"我的空白构筑", false, false}};
    }

    @Test(dataProvider = "slots")
    public void actualCopyUsesOnlyEmptyDefaultSlotsWithoutRenamingOthers(String name, boolean occupied, boolean available) throws Exception {
        MobileTestEnvironment.initialize();
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("forge-gui/res/languages"))) root = root.getParent();
        assertNotNull(root);
        final Constructor<Localizer> constructor = Localizer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        final Localizer localizer = constructor.newInstance();
        localizer.setLanguage("zh-CN", root.resolve("forge-gui/res/languages").toString());
        try (var forge = mockStatic(Forge.class)) {
            forge.when(Forge::getLocalizer).thenReturn(localizer);
            final PaperCard card = mock(PaperCard.class);
            final Deck source = new Deck("保留来源套牌");
            source.getMain().add(card, 2);
            final Deck candidate = new Deck(name);
            if (occupied) candidate.getMain().add(card, 1);
            final ArrayList<Deck> decks = new ArrayList<>(Arrays.asList(source, candidate));
            // Bypass world/UI construction only; all slot-selection, copy and loadout methods below run production code.
            final AdventurePlayer player = mock(AdventurePlayer.class, CALLS_REAL_METHODS);
            field(player, "decks", decks);
            field(player, "deck", source);
            field(player, "maxDeckCount", 2);
            final HashMap<String, Long> loadout = new HashMap<>();
            loadout.put("head", 42L);
            final ArrayList<HashMap<String, Long>> loadouts = new ArrayList<>(Arrays.asList(loadout, null));
            field(player, "deckLoadouts", loadouts);
            assertEquals(player.isEmptyDeck(1), available);
            assertEquals(player.copyDeck(), available ? 1 : -1);
            assertSame(decks.get(0), source);
            assertEquals(source.getName(), "保留来源套牌");
            assertEquals(source.getMain().count(card), 2);
            assertEquals(candidate.getName(), name);
            assertEquals(candidate.getMain().count(card), occupied ? 1 : 0);
            if (available) {
                assertNotSame(decks.get(1), candidate);
                assertEquals(decks.get(1).getMain().count(card), 2);
                assertEquals(decks.get(1).getName(), "保留来源套牌 (" + localizer.getMessage("lblCopy") + ")");
                assertEquals(loadouts.get(1), loadout);
                assertNotSame(loadouts.get(1), loadout);
            } else {
                assertSame(decks.get(1), candidate);
                assertNull(loadouts.get(1));
            }
        }
    }
}
