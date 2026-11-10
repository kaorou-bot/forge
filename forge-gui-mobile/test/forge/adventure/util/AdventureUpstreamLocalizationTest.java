package forge.adventure.util;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.MobileTestEnvironment;
import forge.adventure.data.EnemyData;
import forge.adventure.data.ItemData;
import forge.util.Localizer;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.testng.Assert.*;

/** Incremental review of upstream Adventure and tutorial text changes. */
public class AdventureUpstreamLocalizationTest {
    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("forge-gui/res"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Cannot find resources");
        return path;
    }

    private static Localizer chinese() {
        Localizer localizer = Localizer.getInstance();
        localizer.setLanguage("zh-CN", root().resolve("forge-gui/res/languages").toString());
        localizer.loadAdventureBundle(null);
        return localizer;
    }

    @BeforeClass
    public void initialize() throws Exception {
        MobileTestEnvironment.initialize();
    }

    @Test
    public void renameMessagesKeepPlayerNameAsAnArgument() throws Exception {
        Localizer localizer = chinese();
        String name = "旅人 O'Brien {0} [red]";
        assertEquals(localizer.getMessage("adv.console.rename.success", name), "角色已更名为：" + name);
        assertEquals(localizer.getEnglishMessage("adv.console.rename.success", name), "Renamed player to " + name);
        assertEquals(localizer.getMessage("adv.console.rename.usage"), "此命令需要 1 个参数：角色名称。");
        String code = Files.readString(root().resolve(
                "forge-gui-mobile/src/forge/adventure/stage/ConsoleCommandInterpreter.java"));
        assertTrue(code.contains("getMessage(\"adv.console.rename.success\", newName)"));
        assertTrue(code.contains("getMessage(\"adv.console.rename.usage\")"));
        assertTrue(code.contains("Current.player().setName(newName)"));
        assertTrue(code.contains("GamePlayerUtil.getGuiPlayer().setName(newName)"));
    }

    @Test
    public void tutorialCombatDiagnosticPreservesTheLifeArgument() {
        Localizer localizer = chinese();
        assertEquals(localizer.getMessage("msgTutCombatOver", "20"),
                "战斗已结束，但愤怒鬼怪没有造成伤害。请先检查战斗结果，再继续练习。对手仍有 20 点生命。");
        assertTrue(localizer.getEnglishMessage("msgTutCombatOver", "20").contains("20 life"));
    }

    @Test
    public void correctedEnemyNameUsesDisplayTranslationWithoutChangingIdentity() {
        chinese();
        EnemyData enemy = new EnemyData();
        enemy.name = "Merfolk warrior";
        enemy.nameOverride = "Merfolk Warrior";
        assertEquals(enemy.getName(), "Merfolk Warrior");
        assertEquals(enemy.getDisplayName(), "人鱼战士");
        assertEquals(enemy.name, "Merfolk warrior");
        assertEquals(EnemyData.getDisplayName("Unknown mod enemy"), "Unknown mod enemy");
    }

    @Test
    public void artistBeretDescriptionUsesOneGenericManaReduction() throws Exception {
        Localizer localizer = chinese();
        JsonValue items = new JsonReader().parse(Files.readString(root().resolve(
                "forge-gui/res/adventure/Realm of Legends/world/items.json")));
        JsonValue artist = null;
        for (JsonValue item : items) if ("Artist's Beret".equals(item.getString("name"))) artist = item;
        assertNotNull(artist);
        assertEquals(artist.getString("description"), localizer.getEnglishMessage("adv.item.ArtistsBeret.description"));
        assertEquals(artist.get("effect").get("startBattleWithCard").getString(0), "Artist's Talent|BLB");
        ItemData item = new ItemData();
        item.name = artist.getString("name");
        item.description = artist.getString("description");
        assertEquals(item.getName(), "Artist's Beret");
        assertEquals(item.getDisplayName(), "艺术家的贝雷帽");
        String description = item.getDescription();
        assertTrue(description.contains("少支付 1 点通用法术力"));
        assertTrue(description.contains("再支付 2R 升至 3 级"));
        assertTrue(description.contains("非战斗伤害增加 2 点"));
        assertFalse(description.contains("少支付 2 点"));
        item.name = "Unknown mod item";
        item.description = "Original mod description";
        assertEquals(item.getDescription(), "Original mod description\n");
    }

    private static JsonValue betaDialog() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList objects = factory.newDocumentBuilder().parse(root().resolve(
                "forge-gui/res/adventure/Realm of Legends/maps/map/cave/Card_Dealers_Cave.tmx").toFile())
                .getElementsByTagName("object");
        for (int i = 0; i < objects.getLength(); i++) {
            Element object = (Element) objects.item(i);
            if (!"59".equals(object.getAttribute("id"))) continue;
            NodeList properties = object.getElementsByTagName("property");
            for (int p = 0; p < properties.getLength(); p++) {
                Element property = (Element) properties.item(p);
                if ("dialog".equals(property.getAttribute("name"))) {
                    return new JsonReader().parse(property.getTextContent()).get(0);
                }
            }
        }
        throw new IllegalStateException("Missing Beta card dealer dialog");
    }

    private static int checkDialog(JsonValue node, Localizer localizer, Set<String> keys) {
        Set<String> members = new HashSet<>();
        for (JsonValue child : node) {
            if (node.isObject()) assertTrue(members.add(child.name), "Duplicate member " + child.name);
        }
        int fields = 0;
        for (String field : new String[] {"name", "text"}) {
            String source = node.getString(field, "");
            if (source.isEmpty()) continue;
            String key = node.getString("loc" + field, "");
            assertTrue(key.startsWith("adv.realm.cardDealer.beta."));
            keys.add(key);
            assertEquals(localizer.getEnglishMessage(key), source, key);
            assertTrue(localizer.getMessage(key).codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), key);
            fields++;
        }
        if (node.has("options")) for (JsonValue option : node.get("options")) fields += checkDialog(option, localizer, keys);
        return fields;
    }

    @Test
    public void betaDealerWholeConversationIsLocalizedAndPreservesEnglishFallback() throws Exception {
        Localizer localizer = chinese();
        Set<String> keys = new HashSet<>();
        assertEquals(checkDialog(betaDialog(), localizer, keys), 9);
        assertEquals(keys.size(), 7);
        assertEquals(localizer.getMessage("adv.realm.cardDealer.beta.1"), "我今天运气不错。（花费 25,000 金币）");
    }

    @Test
    public void betaPurchaseAmountMatchesBothConditionsAndDeduction() throws Exception {
        JsonValue options = betaDialog().get("options");
        assertEquals(options.get(0).get("condition").get(0).getInt("hasGold"), 25000);
        assertEquals(options.get(0).get("action").get(0).getInt("addGold"), -25000);
        assertEquals(options.get(1).get("condition").get(0).getInt("hasGold"), 25000);
        assertTrue(options.get(1).get("condition").get(0).getBoolean("not"));
    }

    @Test
    public void bothRewardCountersUseExistingLocalizedLabel() throws Exception {
        String code = Files.readString(root().resolve("forge-gui-mobile/src/forge/adventure/scene/RewardScene.java"));
        assertFalse(code.contains("\"Pick Reward\""));
        assertEquals(code.split("getMessage\\(\"lblPickReward\"\\)", -1).length - 1, 3);
        assertEquals(chinese().getMessage("lblPickReward"), "选择奖励");
    }
}
