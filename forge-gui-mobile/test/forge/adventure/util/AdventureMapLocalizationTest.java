package forge.adventure.util;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.MobileTestEnvironment;
import forge.adventure.data.DialogData;
import forge.util.Localizer;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.testng.Assert.*;

public class AdventureMapLocalizationTest {
    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("forge-gui/res"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException("Cannot find resources");
        }
        return path;
    }

    private static void language(final String language) {
        Localizer.getInstance().setLanguage(language, root().resolve("forge-gui/res/languages").toString());
        Localizer.getInstance().loadAdventureBundle(null);
    }

    private static List<String> tokens(final String text) {
        final List<String> tokens = new ArrayList<>();
        final Matcher matcher = Pattern.compile("\\{[^{}]+\\}").matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    @BeforeClass
    public void initialize() throws Exception {
        MobileTestEnvironment.initialize();
    }

    @Test
    public void everyBoundCommonMapFieldFormatsInChineseAndRetainsRuntimeTokens() throws Exception {
        language("zh-CN");
        final Set<String> keys = new HashSet<>();
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final Pattern binding = Pattern.compile(
                "\"(text|name)\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")\\s*,?\\s*\"loc\\1\"\\s*:\\s*\"([^\"]+)\"");
        try (var paths = Files.walk(root().resolve("forge-gui/res/adventure/common/maps"))) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".tmx") || p.toString().endsWith(".tx")).toList()) {
                final NodeList properties = factory.newDocumentBuilder().parse(path.toFile())
                        .getElementsByTagName("property");
                for (int p = 0; p < properties.getLength(); p++) {
                    final Element property = (Element) properties.item(p);
                    if (!property.getAttribute("name").toLowerCase(java.util.Locale.ROOT).contains("dialog")) {
                        continue;
                    }
                    final String json = property.hasAttribute("value")
                            ? property.getAttribute("value") : property.getTextContent();
                    final Matcher matcher = binding.matcher(json);
                    while (matcher.find()) {
                        final String source = new JsonReader().parse(matcher.group(2)).asString();
                        final String key = matcher.group(3);
                        keys.add(key);
                        final String translation = Localizer.getInstance().getMessage(key);
                        assertFalse(translation.contains("INVALID PROPERTY"), path + ": " + key);
                        assertEquals(tokens(translation), tokens(source), key);
                        assertFalse(translation.contains("'{'"), key);
                        assertTrue(translation.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)
                                || source.equals("...") || source.equals("???"), key);
                    }
                }
            }
        }
        assertEquals(keys.size(), 831);
    }

    private static void assertUniqueMembers(final JsonValue node, final String path) {
        final Set<String> names = new HashSet<>();
        for (JsonValue child = node.child; child != null; child = child.next) {
            if (node.isObject()) {
                assertTrue(names.add(child.name), path + ": duplicate member " + child.name);
            }
            assertUniqueMembers(child, path + "/" + child.name);
        }
    }

    @Test
    public void templeDialogBindingsHaveNoDuplicateJsonMembers() throws Exception {
        final Path map = root().resolve("forge-gui/res/adventure/Shandalar Old Border/maps/map/"
                + "fallen_empires/svyelunite_temple.tmx");
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final NodeList properties = factory.newDocumentBuilder().parse(map.toFile())
                .getElementsByTagName("property");
        int checked = 0;
        for (int p = 0; p < properties.getLength(); p++) {
            final Element property = (Element) properties.item(p);
            if (!property.getAttribute("name").toLowerCase(java.util.Locale.ROOT).contains("dialog")) {
                continue;
            }
            final String json = property.hasAttribute("value")
                    ? property.getAttribute("value") : property.getTextContent();
            if (json.isBlank()) {
                continue;
            }
            assertUniqueMembers(new JsonReader().parse(json), map + "/property[" + p + "]");
            checked++;
        }
        assertTrue(checked > 0);
    }

    private static void checkLocalizedDialog(final JsonValue node, final String path,
                                              final Set<String> addedKeys) {
        if (node.isArray()) {
            for (JsonValue child = node.child; child != null; child = child.next) {
                checkLocalizedDialog(child, path, addedKeys);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (String field : new String[] {"name", "text"}) {
            final String source = node.getString(field, "");
            if (source.isBlank()) {
                continue;
            }
            final String key = node.getString("loc" + field, "");
            assertFalse(key.isBlank(), path + ": unbound " + source);
            final String translation = Localizer.getInstance().getMessage(key);
            assertFalse(translation.contains("INVALID PROPERTY"), path + ": " + key);
            if (source.equals("...")) {
                assertEquals(translation, "……", key);
            } else {
                assertTrue(translation.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), key);
            }
            assertEquals(tokens(translation), tokens(source), key);
            if (key.startsWith("adv.realm.main.") || key.startsWith("adv.oldborder.plot.")
                    || key.startsWith("adv.oldborder.explore.") || key.startsWith("adv.innistrad.opening.")) {
                addedKeys.add(key);
            }
        }
        final JsonValue options = node.get("options");
        if (options != null) {
            checkLocalizedDialog(options, path + "/options", addedKeys);
        }
    }

    @Test
    public void capitalPalaceAndTempleDialogsAreFullyLocalized() throws Exception {
        language("zh-CN");
        final Set<String> addedKeys = new HashSet<>();
        final List<Path> maps = new ArrayList<>();
        for (String name : new String[] {"spawn", "forest_capital", "island_capital",
                "mountain_capital", "plains_capital", "swamp_capital"}) {
            maps.add(root().resolve("forge-gui/res/adventure/Realm of Legends/maps/map/main_story/"
                    + name + ".tmx"));
        }
        for (String color : new String[] {"black", "blue", "green", "red", "white"}) {
            maps.add(root().resolve("forge-gui/res/adventure/Shandalar Old Border/maps/map/end_palace/"
                    + color + "_end_palace.tmx"));
        }
        maps.add(root().resolve("forge-gui/res/adventure/Shandalar Old Border/maps/map/"
                + "fallen_empires/svyelunite_temple.tmx"));
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (Path map : maps) {
            final NodeList properties = factory.newDocumentBuilder().parse(map.toFile())
                    .getElementsByTagName("property");
            for (int p = 0; p < properties.getLength(); p++) {
                final Element property = (Element) properties.item(p);
                if (!property.getAttribute("name").toLowerCase(java.util.Locale.ROOT).contains("dialog")) {
                    continue;
                }
                final String json = property.hasAttribute("value")
                        ? property.getAttribute("value") : property.getTextContent();
                if (json.isBlank()) {
                    continue;
                }
                final JsonValue dialog = new JsonReader().parse(json);
                assertUniqueMembers(dialog, map + "/property[" + p + "]");
                checkLocalizedDialog(dialog, map.toString(), addedKeys);
            }
        }
        assertEquals(addedKeys.size(), 293);
    }

    @Test
    public void oldBorderAndInnistradMapDialogsAreFullyLocalized() throws Exception {
        language("zh-CN");
        final Set<String> addedKeys = new HashSet<>();
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        int dialogMaps = 0;
        for (String world : new String[] {"Shandalar Old Border", "Innistrad"}) {
            try (var paths = Files.walk(root().resolve("forge-gui/res/adventure/" + world + "/maps"))) {
                for (Path map : paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".tmx") || p.toString().endsWith(".tx")).toList()) {
                    final NodeList properties = factory.newDocumentBuilder().parse(map.toFile())
                            .getElementsByTagName("property");
                    boolean hasDialog = false;
                    for (int p = 0; p < properties.getLength(); p++) {
                        final Element property = (Element) properties.item(p);
                        if (!property.getAttribute("name").toLowerCase(java.util.Locale.ROOT).contains("dialog")) {
                            continue;
                        }
                        final String json = property.hasAttribute("value")
                                ? property.getAttribute("value") : property.getTextContent();
                        if (json.isBlank()) {
                            continue;
                        }
                        hasDialog = true;
                        final JsonValue dialog = new JsonReader().parse(json);
                        assertUniqueMembers(dialog, map + "/property[" + p + "]");
                        checkLocalizedDialog(dialog, map.toString(), addedKeys);
                    }
                    if (hasDialog) {
                        dialogMaps++;
                    }
                }
            }
        }
        assertEquals(dialogMaps, 87);
        assertEquals(addedKeys.stream().filter(key -> key.startsWith("adv.oldborder.explore.")).count(), 181L);
        assertEquals(addedKeys.stream().filter(key -> key.startsWith("adv.innistrad.opening.")).count(), 20L);
    }

    @Test
    public void gateActionUsesOriginalTextInEitherLanguage() {
        final DialogData gate = new DialogData();
        gate.text = "YOU ARE NOT WORTHY, BEGONE!";
        gate.loctext = "adv.main_story_castles_black_castle.55.d.n7.text";
        for (String language : new String[] {"zh-CN", "en-US"}) {
            language(language);
            final String displayed = Localizer.getInstance().getMessageorUseDefault(gate.loctext, gate.text);
            assertEquals(displayed, language.equals("zh-CN") ? "你还不够格，退下！" : gate.text);
            assertTrue(MapDialog.isBlockedGateDialog(gate));
            gate.text = "The gate is unlocked.";
            assertFalse(MapDialog.isBlockedGateDialog(gate));
            gate.text = "YOU ARE NOT WORTHY, BEGONE!";
        }
    }

    @Test
    public void englishAndUnknownKeysRetainSourceAndPlayerVariableIsNotConsumed() {
        final String key = "adv.main_story_spawn.69.d.n22.text";
        final String original = "\"{var=player_name}! I feel the Guardians' spell growing weaker! Defeat them all and you may save us yet.\"";
        language("en-US");
        assertEquals(Localizer.getInstance().getMessageorUseDefault(key, original), original);
        language("zh-CN");
        final String translated = Localizer.getInstance().getMessageorUseDefault(key, original);
        assertTrue(translated.contains("{var=player_name}"));
        assertFalse(translated.equals(original));
        assertEquals(Localizer.getInstance().getMessageorUseDefault("missing.map.key", original), original);
    }
}
