package forge.adventure.util;

import com.badlogic.gdx.utils.Json;
import forge.MobileTestEnvironment;
import forge.adventure.data.ModifierData;
import forge.card.CardRarity;
import forge.card.ColorSet;
import forge.util.Localizer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.testng.Assert.*;

/** Display translations must not become modifier configuration/save identifiers. */
public class AdventureModifierLocalizationTest {
    private Path languages;

    @BeforeClass public void initialize() throws Exception {
        MobileTestEnvironment.initialize();
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("forge-gui/res"))) root = root.getParent();
        assertNotNull(root);
        languages = root.resolve("forge-gui/res/languages");
    }

    private void language(String locale) {
        Localizer.getInstance().setEnglish(false);
        Localizer.getInstance().setLanguage(locale, languages.toString());
        Localizer.getInstance().loadAdventureBundle(null);
    }

    @AfterMethod public void restoreChinese() { language("zh-CN"); }

    @Test public void rarityLabelsPreserveCanonicalSavedValues() {
        ModifierData modifier = new ModifierData();
        modifier.name = "Rarity limit"; modifier.type = ModifierData.Type.RarityLimit;
        modifier.description = "Original description";
        language("zh-CN");
        assertEquals(modifier.getDisplayName(), "稀有度限制");
        assertTrue(modifier.getDescription().contains("基本地"));
        String[] labels = {"普通", "非普通", "稀有"};
        for (int i = 0; i < ModifierData.RARITIES.length; i++) {
            CardRarity rarity = ModifierData.RARITIES[i];
            assertEquals(modifier.describeValue(rarity.name()), labels[i]);
            assertEquals(modifier.parseValue(rarity.name().toLowerCase()), rarity.name());
            assertNull(modifier.parseValue(labels[i]));
        }
        assertEquals(modifier.defaultValue(ColorSet.fromNames(new char[] {'W'})), "Common");
        assertEquals(modifier.name, "Rarity limit");
        language("en-US");
        assertEquals(modifier.describeValue("Common"), "Common");
    }

    @Test public void lossModifierSerializationDoesNotAcquireTranslatedIdentifiers() {
        ModifierData modifier = new ModifierData();
        modifier.name = "Permadeath"; modifier.description = "Original description";
        modifier.type = ModifierData.Type.CardLoss; modifier.lostCards = 5; modifier.loseCommander = true;
        Json json = new Json();
        String before = json.toJson(modifier);
        language("zh-CN");
        assertEquals(modifier.getDisplayName(), "败战惩罚");
        assertTrue(modifier.getDescription().contains("5"));
        assertEquals(json.toJson(modifier), before);
        ModifierData copy = json.fromJson(ModifierData.class, before);
        assertEquals(copy.name, "Permadeath");
        assertEquals(copy.type, ModifierData.Type.CardLoss);
        assertEquals(copy.lostCards, 5); assertTrue(copy.loseCommander);
    }

    @Test public void colorAndUnknownModifiersKeepConfigValuesAndFallbackText() {
        ModifierData modifier = new ModifierData();
        modifier.name = "Color limit"; modifier.type = ModifierData.Type.ColorLimit;
        language("zh-CN");
        assertEquals(modifier.getDisplayName(), "颜色限制");
        assertEquals(modifier.parseValue("wu"), ColorSet.fromNames(new char[] {'W', 'U'}).toString());
        assertNull(modifier.parseValue("白蓝"));
        assertNull(modifier.parseValue("invalid"));
        modifier.name = "Unknown custom rule"; modifier.description = "Keep {tokens} and original words";
        assertEquals(modifier.getDisplayName(), modifier.name);
        assertEquals(modifier.getDescription(), modifier.description);
    }
}
