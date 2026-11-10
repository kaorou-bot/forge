package forge.adventure.util;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
import com.badlogic.gdx.utils.Array;
import forge.MobileTestEnvironment;
import forge.item.IPaperCard;
import forge.adventure.data.EffectData;
import forge.adventure.data.ItemData;
import forge.util.CardTranslation;
import forge.util.Localizer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.*;
import static org.mockito.Mockito.*;

public class AdventureItemLocalizationTest {
    private static final String[] WORLDS = {"common", "Innistrad", "Realm of Legends", "Shandalar Old Border"};

    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("forge-gui/res"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Cannot find resources");
        return path;
    }

    private static Localizer language(String locale) {
        Localizer localizer = Localizer.getInstance();
        localizer.setEnglish(false);
        localizer.setLanguage(locale, root().resolve("forge-gui/res/languages").toString());
        localizer.loadAdventureBundle(null);
        return localizer;
    }

    @BeforeClass
    public void initialize() throws Exception {
        MobileTestEnvironment.initialize();
    }

    @AfterMethod
    public void restoreChinese() {
        language("zh-CN");
        CardTranslation.preloadTranslation("zh-CN", root().resolve("forge-gui/res/languages").toString() + "/");
    }

    private static JsonValue items(String world) throws Exception {
        return new JsonReader().parse(Files.readString(root().resolve(
                "forge-gui/res/adventure/" + world + "/world/items.json")));
    }

    private static ItemData item(String world, String name) throws Exception {
        for (JsonValue data : items(world)) {
            if (name.equals(data.getString("name"))) {
                return new Json().fromJson(ItemData.class, data.toJson(JsonWriter.OutputType.json));
            }
        }
        throw new IllegalArgumentException("Missing item " + name);
    }

    @Test
    public void screenshotRuneHasChineseNameDescriptionAndSlotWithoutChangingIdentity() throws Exception {
        language("zh-CN");
        ItemData rune = item("common", "Colorless rune");
        assertEquals(rune.getDisplayName(), "无色符文");
        assertTrue(rune.getDescription().startsWith("将你传送回地图中央的起始地点。\n装备栏位：技能栏 2\n"));
        assertTrue(rune.getDescription().contains("1 [+Shards]"));
        assertEquals(rune.getName(), "Colorless rune");
        assertEquals(rune.equipmentSlot, "Ability2");
        assertEquals(rune.commandOnUse, "teleport to poi Spawn");
    }

    @Test
    public void allBoundItemsHaveMatchingEnglishAndChineseDisplayFields() throws Exception {
        Localizer localizer = language("zh-CN");
        int bound = 0;
        Set<String> names = new HashSet<>();
        Map<String, String> keyOwners = new HashMap<>();
        for (String world : WORLDS) {
            for (JsonValue data : items(world)) {
                assertTrue(data.has("locdescription"), world + ":" + data.getString("name"));
                String name = data.getString("name");
                String descriptionKey = data.getString("locdescription");
                String nameKey = "adv.item." + name.replaceAll("[^A-Za-z0-9]", "") + ".displayName";
                String original = data.getString("description", "");
                assertEquals(localizer.getEnglishMessage(descriptionKey), original, world + ":" + name);
                assertEquals(localizer.getEnglishMessage(nameKey), name);
                String previousOwner = keyOwners.put(nameKey, name);
                assertTrue(previousOwner == null || previousOwner.equals(name), "Name key collision: " + nameKey);
                for (String key : new String[] {descriptionKey, nameKey}) {
                    if (key.equals(descriptionKey) && original.isEmpty()) {
                        assertEquals(localizer.getMessage(key), "");
                        continue;
                    }
                    assertTrue(localizer.getMessage(key).codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), key);
                    assertFalse(localizer.getMessage(key).contains("INVALID PROPERTY"));
                }
                names.add(name);
                bound++;
            }
        }
        assertEquals(bound, 1232);
        assertEquals(names.size(), 951);
    }

    @Test
    public void worldSpecificDescriptionsDoNotLeakIntoOtherWorlds() throws Exception {
        language("zh-CN");
        assertTrue(item("Realm of Legends", "Colorless rune").getDescription().contains("神秘图书馆"));
        assertFalse(item("common", "Colorless rune").getDescription().contains("神秘图书馆"));
        assertTrue(item("Realm of Legends", "Silver Challenge Coin").getDescription().contains("现开赛事"));
        assertTrue(item("Shandalar Old Border", "Silver Challenge Coin").getDescription().contains("免费现开赛事"));
        assertFalse(item("Shandalar Old Border", "Silver Challenge Coin").getDescription().contains("目前没有用途"));
        assertEquals(language("zh-CN").getMessage(item("common", "Iron Armor").locdescription), "");
        assertFalse(language("zh-CN").getMessage(item("Shandalar Old Border", "Iron Armor").locdescription).isEmpty());
        language("en-US");
        assertTrue(item("Realm of Legends", "Colorless rune").getDescription().startsWith("Teleports you to the mysterious library\nSlot: Ability2\n"));
        assertTrue(item("Shandalar Old Border", "Silver Challenge Coin").getDescription()
                .startsWith("A weighty coin made of silver. Redeem for a free sealed."));
    }

    @Test
    public void dwarvenRuneStoneHasExplicitTranslationWithoutChangingQuestIdentity() throws Exception {
        language("zh-CN");
        ItemData rune = item("Shandalar Old Border", "Dwarven Rune Stone");
        assertEquals(rune.getName(), "Dwarven Rune Stone");
        assertEquals(rune.locdescription, "adv.item.DwarvenRuneStone.description");
        assertEquals(rune.getDisplayName(), "矮人符文石");
        assertTrue(rune.getDescription().startsWith("矮人小马商人赠予的符文石，可以打开通往矮人要塞的暗门。"));
        assertTrue(rune.questItem);
        language("en-US");
        assertEquals(rune.getDisplayName(), "Dwarven Rune Stone");
        assertTrue(rune.getDescription().startsWith("A rune stone given to you by a Dwarven Pony Trader."));
    }

    @Test
    public void everyExistingSlotHasChineseDisplayText() throws Exception {
        Localizer localizer = language("zh-CN");
        Set<String> slots = new HashSet<>();
        for (String world : WORLDS) {
            for (JsonValue data : items(world)) {
                String slot = data.getString("equipmentSlot", "");
                if (!slot.isEmpty()) slots.add(slot);
            }
        }
        assertEquals(slots.size(), 14);
        for (String slot : slots) {
            String key = "adv.item.slot." + slot;
            assertEquals(localizer.getEnglishMessage(key), slot);
            assertNotEquals(localizer.getMessage(key), slot);
            assertFalse(localizer.getMessage(key).contains("INVALID PROPERTY"));
        }
    }

    @Test
    public void legacyAndUnknownItemsKeepSourceFallbackAndSerializationKeys() throws Exception {
        language("zh-CN");
        ItemData mod = new ItemData();
        mod.name = "Mod O'Brien {0}";
        mod.description = "Original {0} [red] description";
        mod.equipmentSlot = "ModSlot";
        mod.locdescription = "missing.mod.key";
        assertEquals(mod.getDisplayName(), mod.name);
        assertEquals(mod.getDescription(), mod.description + "\n装备栏位：ModSlot\n");
        mod.effect = new EffectData();
        assertEquals(new ItemData(mod).locdescription, mod.locdescription);
        assertEquals(mod.clone().locdescription, mod.locdescription);
        assertEquals(new Json().fromJson(ItemData.class, new Json().toJson(mod)).locdescription, mod.locdescription);
        ItemData old = new ItemData();
        old.name = "Artist's Beret";
        old.description = "Original description";
        assertTrue(old.getDescription().contains("少支付 1 点通用法术力"));
        old.name = "Unknown legacy item";
        assertEquals(old.getDescription(), "Original description\n");
    }

    @Test
    public void oldSavedItemsAcquireOnlyDisplayKeys() throws Exception {
        language("zh-CN");
        ItemData definition = item("Realm of Legends", "Colorless rune");
        ItemData saved = definition.clone();
        saved.locdescription = null;
        saved.longID = 123L;
        saved.isEquipped = true;
        saved.isCracked = true;
        saved.cost = 17;
        saved.updateLocalizationFrom(definition);
        assertTrue(saved.getDescription().contains("神秘图书馆"));
        assertEquals(saved.longID.longValue(), 123L);
        assertTrue(saved.isEquipped && saved.isCracked);
        assertEquals(saved.cost, 17);
        assertEquals(saved.commandOnUse, definition.commandOnUse);
        saved.locdescription = null;
        saved.description = "Modified by a mod";
        saved.updateLocalizationFrom(definition);
        assertNull(saved.locdescription);
        saved.updateLocalizationFrom(null);
        assertNull(saved.locdescription);
        String loader = Files.readString(root().resolve("forge-gui-mobile/src/forge/adventure/player/AdventurePlayer.java"));
        assertTrue(loader.contains("itemData.updateLocalizationFrom(ItemListData.getItem(itemData.name))"));
    }

    @Test
    public void dynamicEffectsLocalizeLabelsWithoutChangingValuesOrGlyphs() {
        language("zh-CN");
        EffectData effect = new EffectData();
        effect.colorView = true;
        effect.lifeModifier = 2;
        effect.changeStartCards = -1;
        effect.goldModifier = 0.8f;
        effect.opponent = new EffectData();
        effect.opponent.changeStartCards = 1;
        String text = effect.getDescription();
        assertTrue(text.contains("魔力视野"));
        assertTrue(text.contains("[+Life] +2"));
        assertTrue(text.contains("起手牌数量调整：-1"));
        assertTrue(text.contains("商店价格倍率：×0.8"));
        assertTrue(text.contains("给予对手的效果：\n起手牌数量调整：1"));
        language("en-US");
        assertTrue(effect.getDescription().contains("Starting hand: -1\n"));
        assertTrue(effect.getDescription().contains("Shop discount: x0.8\n"));
        assertEquals(effect.lifeModifier, 2);
        assertEquals(effect.changeStartCards, -1);
    }

    @Test
    public void effectCardNamesUseCardTranslationsCustomFallbackAndEnglish() {
        language("zh-CN");
        String directory = root().resolve("forge-gui/res/languages").toString() + "/";
        CardTranslation.preloadTranslation("zh-CN", directory);
        Array<IPaperCard> cards = new Array<>(IPaperCard.class);
        IPaperCard official = mock(IPaperCard.class);
        when(official.getName()).thenReturn("Ankh of Mishra");
        IPaperCard custom = mock(IPaperCard.class);
        when(custom.getName()).thenReturn("Chandra's Tome");
        IPaperCard unknown = mock(IPaperCard.class);
        when(unknown.getName()).thenReturn("Unknown Mod Card");
        cards.add(official);
        cards.add(custom);
        cards.add(custom);
        cards.add(unknown);
        EffectData effect = new EffectData();
        String translated = effect.itemize(cards);
        assertTrue(translated.contains("1x 米斯拉的神符"));
        assertTrue(translated.contains("2x 茜卓的典籍"));
        assertTrue(translated.contains("1x Unknown Mod Card"));
        language("en-US");
        CardTranslation.preloadTranslation("en-US", directory);
        String english = effect.itemize(cards);
        assertTrue(english.contains("1x Ankh of Mishra"));
        assertTrue(english.contains("2x Chandra's Tome"));
    }

    @Test
    public void correctedSummariesKeepCriticalCostsConditionsAndSideEffects() {
        Localizer localizer = language("zh-CN");
        String fool = localizer.getMessage("adv.item.FoolsOutfit.description");
        assertTrue(fool.contains("支付 2 并横置"));
        assertTrue(fool.contains("没有手牌"));
        assertFalse(fool.contains("跳过"));
        assertTrue(localizer.getMessage("adv.item.FurnaceSkullbomb.description").contains("牺牲此神器"));
        assertTrue(localizer.getMessage("adv.item.FurnaceSkullbomb.description").contains("法术时机"));
        assertTrue(localizer.getMessage("adv.item.CephalidArmor.description").contains("至少有七张牌"));
        assertTrue(localizer.getMessage("adv.item.CephalidArmor.description").contains("对你造成 1 点伤害"));
        assertTrue(localizer.getMessage("adv.item.BelbesBoots.description").contains("-X/+X"));
        assertTrue(localizer.getMessage("adv.item.KryGreaves.description").contains("将造成的所有伤害"));
        assertTrue(localizer.getMessage("adv.item.SorcerersWand.description").contains("横置"));
        assertTrue(localizer.getMessage("adv.item.SorcerersWand.description").contains("鹏洛客"));
        assertTrue(localizer.getMessage("adv.item.AleshasWarSkirt.description").contains("横置移回战场"));
        String mardu = localizer.getMessage("adv.item.MarduGauntlet.description");
        assertFalse(mardu.contains("回合末将其牺牲"));
        assertTrue(localizer.getMessage("adv.item.ElixirofVitality.description").contains("横置进场"));
        assertTrue(localizer.getMessage("adv.item.ManasightAmulet.description.variant3").contains("对局起始生命"));
    }
}
