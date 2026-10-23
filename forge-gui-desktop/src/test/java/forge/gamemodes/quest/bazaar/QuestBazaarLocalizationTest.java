package forge.gamemodes.quest.bazaar;

import forge.gamemodes.quest.data.QuestAssets;
import forge.gamemodes.quest.QuestMatchLength;
import forge.util.Localizer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.testng.Assert.*;

public class QuestBazaarLocalizationTest {
    private static Path resources() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.isDirectory(path.resolve("forge-gui/res"))) {
                return path.resolve("forge-gui/res");
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Cannot locate resources");
    }

    private static void language(final String locale) {
        Localizer.getInstance().setEnglish(false);
        Localizer.getInstance().setLanguage(locale, resources().resolve("languages").toString());
    }

    @AfterMethod
    public void resetLanguage() {
        language("en-US");
    }

    @Test
    public void stallsKeepInternalKeysAndHaveShortChineseLabels() {
        language("zh-CN");
        final QuestBazaarManager manager = new QuestBazaarManager(resources().resolve("quest/bazaar/index.xml").toFile());
        assertEquals(manager.getStallNames().size(), 6);
        final QuestStallDefinition stall = manager.getStall("Alchemist");
        assertEquals(stall.getName(), "Alchemist");
        assertEquals(stall.getLabel(), "炼金师");
        assertEquals(stall.getDisplayName(), "撒姆尼医疗员欧琳");
        assertTrue(stall.getFluff().contains("炼金师"));
        assertTrue(stall.getItems().contains("Elixir of Life"));
        language("en-US");
        assertEquals(stall.getDisplayName(), "Orim, Samite Healer");
        assertTrue(stall.getFluff().contains("alchemist's"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void itemsKeepSaveKeysAndNumericFormatters() throws Exception {
        language("zh-CN");
        final QuestBazaarManager manager = new QuestBazaarManager(resources().resolve("quest/bazaar/index.xml").toFile());
        manager.load();
        final Field field = QuestBazaarManager.class.getDeclaredField("items");
        field.setAccessible(true);
        final Map<String, IQuestBazaarItem> items = (Map<String, IQuestBazaarItem>) field.get(manager);
        assertEquals(items.size(), 11);
        for (IQuestBazaarItem item : items.values()) {
            final QuestItemBasic basic = (QuestItemBasic) item;
            assertEquals(basic.getName(), basic.getItemType().getKey());
            assertNotEquals(basic.getPurchaseName(), basic.getName());
        }
        final QuestAssets assets = new QuestAssets();
        final QuestItemBasic estates = (QuestItemBasic) items.get("Estates");
        final int oldPrice = estates.getBuyingPrice(assets);
        assertTrue(estates.getPurchaseDescription(assets).contains("10%"));
        assertTrue(estates.getPurchaseDescription(assets).contains("1"));
        estates.onPurchase(assets);
        assertEquals(assets.getItemLevel(QuestItemType.ESTATES), 1);
        assertTrue(estates.getPurchaseDescription(assets).contains("15%"));
        language("en-US");
        assertEquals(estates.getName(), "Estates");
        assertEquals(estates.getBuyingPrice(new QuestAssets()), oldPrice);
        assertTrue(estates.getPurchaseDescription(assets).contains("15%"));
    }

    @Test
    public void allPetLevelsKeepSymbolsAndSaveIdentity() {
        language("zh-CN");
        final QuestPetStorage storage = new QuestPetStorage(resources().resolve("quest/bazaar/index.xml").toFile());
        int count = 0;
        for (int slot = 0; slot < 2; slot++) {
            for (QuestPetController pet : storage.getAllPets(slot)) {
                count++;
                final QuestAssets assets = new QuestAssets();
                assertEquals(pet.getSaveFileKey(), pet.getName());
                assertSame(storage.getPet(pet.getName()), pet);
                assertNotEquals(pet.getPurchaseName(), pet.getName());
                for (int level = 0; level <= pet.getMaxLevel(); level++) {
                    assets.setPetLevel(pet.getSaveFileKey(), level);
                    assertFalse(pet.getStats(assets).contains("INVALID PROPERTY"));
                    assertTrue(pet.getUpgradeDescription(assets).codePoints().anyMatch(c -> c > 0x4e00));
                    assertTrue(pet.getPurchaseDescription(assets).contains("当前属性："));
                    if (level > 0) {
                        assertTrue(pet.getStats(assets).matches("(?s).*\\{[WUBRG]\\}.*"));
                    }
                }
                assertEquals(pet.getUpgradedStats(assets), "已达最高等级");
            }
        }
        assertEquals(count, 6);
        final QuestAssets houndAssets = new QuestAssets();
        houndAssets.setPetLevel("Hound", 4);
        assertEquals(storage.getPet("Hound").getStats(houndAssets),
                "2/1 {R} 敏捷；{X}{1}：得 +X/+0 直到回合结束，在下一个结束步骤开始时牺牲它");
    }

    @Test
    public void matchLengthLabelsRoundTripWithoutParsingTheirLastCharacter() {
        for (String locale : new String[] {"zh-CN", "en-US"}) {
            language(locale);
            for (int value : new int[] {1, 3, 5}) {
                final String label = QuestMatchLength.labelForValue(value);
                assertEquals(QuestMatchLength.valueForLabel(label), Integer.valueOf(value));
            }
            assertNull(QuestMatchLength.valueForLabel(null));
            assertNull(QuestMatchLength.valueForLabel(""));
            assertNull(QuestMatchLength.valueForLabel("Unknown label 3"));
        }
        language("zh-CN");
        assertEquals(QuestMatchLength.labelForValue(3), "三局两胜");
    }

    @Test
    public void everyBazaarKeyFormatsAndUnknownEntriesRetainOriginalText() throws Exception {
        language("zh-CN");
        final Properties english = new Properties();
        try (Reader reader = Files.newBufferedReader(resources().resolve("languages/en-US.properties"), StandardCharsets.UTF_8)) {
            english.load(reader);
        }
        int count = 0;
        for (String key : english.stringPropertyNames()) {
            if (!key.startsWith("questBazaar.")) {
                continue;
            }
            count++;
            final String text = Localizer.getInstance().getMessage(key, "甲", "{G}", "丙");
            assertFalse(text.contains("INVALID PROPERTY"), key);
            assertFalse(text.contains("Current stats"), key);
        }
        assertEquals(count, 118);
        final String original = "Custom pet O'Brien {G} %d";
        assertEquals(QuestBazaarLocalization.text("pet", "Custom", "description", original), original);
        assertEquals(String.format(Localizer.getInstance().getMessage(
                "questBazaar.item.POUND_FLESH.description"), 250).contains("250"), true);
    }
}
