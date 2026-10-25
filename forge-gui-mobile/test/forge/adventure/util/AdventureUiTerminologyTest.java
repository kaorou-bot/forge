package forge.adventure.util;

import forge.util.Localizer;
import org.testng.annotations.Test;

import java.io.Reader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.testng.Assert.*;

public class AdventureUiTerminologyTest {
    private static final String[] KEYS = ("lblSlot lblWinLossRatio lblChaos lblPile lblCustom lblStarterEdition "
            + "lblInsane lblDispose lblZoom lblEffect lblEmblem lblChangelog lblDisableWinLose lblExitToWoldMap "
            + "lblEmptyDeck lblNoAvailableSlots lblChaosModeDescription lblCustomModeDescription lblSummary "
            + "advModeStandardSummary advDiffEasyStandard advDiffNormalStandard advDiffHardStandard advDiffInsaneStandard "
            + "advDiffNormalConstructed advDiffHardConstructed advDiffInsaneConstructed advModePileSummary "
            + "advDiffEasyPile advDiffNormalPile advDiffHardPile advDiffInsanePile advPileLessRareReward "
            + "advModeCustomGeneticSummary advSealedChampionPrize").split(" ");

    private static Path languages() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("forge-gui/res/languages"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Cannot find languages");
        return path.resolve("forge-gui/res/languages");
    }

    private static Localizer localizer(Path directory, String language) throws Exception {
        // Avoid changing the global language while other localization suites share this JVM.
        final Constructor<Localizer> constructor = Localizer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        final Localizer localizer = constructor.newInstance();
        localizer.setLanguage(language, directory.toString());
        return localizer;
    }

    private static Properties read(Path file) throws Exception {
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Set<String> parameters(String text) {
        final Set<String> result = new TreeSet<>();
        final Matcher matcher = Pattern.compile("\\{[0-9]+\\}").matcher(text);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    @Test
    public void revisedKeysRetainParametersAndFormatInBothLanguages() throws Exception {
        final Properties chinese = read(languages().resolve("zh-CN.properties"));
        final Properties english = read(languages().resolve("en-US.properties"));
        final Localizer zh = localizer(languages(), "zh-CN");
        final Localizer en = localizer(languages(), "en-US");
        assertEquals(KEYS.length, 35);
        for (String key : KEYS) {
            assertTrue(chinese.containsKey(key) && english.containsKey(key), key);
            assertEquals(parameters(chinese.getProperty(key)), parameters(english.getProperty(key)), key);
            assertEquals(zh.getMessage(key, "17"), MessageFormat.format(chinese.getProperty(key), "17"), key);
            assertEquals(en.getMessage(key, "17"), MessageFormat.format(english.getProperty(key), "17"), key);
            assertFalse(zh.getMessage(key, "17").contains("INVALID PROPERTY"), key);
            assertTrue(parameters(zh.getMessage(key, "17")).isEmpty(), key);
        }
        assertEquals(zh.getMessage("advSealedChampionPrize", 17), "冠军（3 胜）：保留你的现开牌池（全部 17 包补充包中的牌）\n");
    }

    @Test
    public void terminologyReflectsActualUiActionsAndStartingDeckScope() throws Exception {
        final Localizer zh = localizer(languages(), "zh-CN");
        final Map<String, String> expected = Map.ofEntries(
                Map.entry("lblPile", "随机牌堆"), Map.entry("lblCustom", "自定义"), Map.entry("lblChaos", "混沌"),
                Map.entry("lblSlot", "存档栏位"), Map.entry("lblEmptyDeck", "空套牌"),
                Map.entry("lblNoAvailableSlots", "没有可用的套牌栏位"), Map.entry("lblZoom", "查看地图"),
                Map.entry("lblDispose", "丢弃"), Map.entry("lblEmblem", "徽记"), Map.entry("lblEffect", "效果"),
                Map.entry("lblDisableWinLose", "隐藏对局胜负提示"), Map.entry("lblWinLossRatio", "胜负比"),
                Map.entry("lblExitToWoldMap", "离开此区域并返回世界地图？"));
        for (Map.Entry<String, String> entry : expected.entrySet()) assertEquals(zh.getMessage(entry.getKey()), entry.getValue());
        assertEquals(zh.getMessage("advPileLessRareReward"), "起始套牌中的非普通牌和稀有牌比普通难度更少。");
        for (String difficulty : new String[] {"Easy", "Normal", "Hard", "Insane"}) {
            assertTrue(zh.getMessage("advDiff" + difficulty + "Standard").contains("Jumpstart 主题包"));
        }
        assertTrue(zh.getMessage("advDiffNormalConstructed").contains("友好色"));
        assertTrue(zh.getMessage("advDiffHardConstructed").contains("对色"));
    }

    @Test
    public void missingChineseTranslationUsesEnglishWithArguments() throws Exception {
        final String configuredRoot = System.getProperty("forge.independent.testRoot");
        final Path fixture = configuredRoot == null || configuredRoot.isBlank()
                ? Files.createTempDirectory("forge-terminology-fallback-")
                : Files.createTempDirectory(Path.of(configuredRoot), "fallback-");
        Files.copy(languages().resolve("en-US.properties"), fixture.resolve("en-US.properties"));
        Files.writeString(fixture.resolve("zh-CN.properties"), "lblCustom=自定义\n", StandardCharsets.UTF_8);
        final Localizer zh = localizer(fixture, "zh-CN");
        assertEquals(zh.getMessage("lblCustom"), "自定义");
        assertEquals(zh.getMessage("lblPile"), "Pile");
        assertEquals(zh.getMessage("advSealedChampionPrize", 17),
                "Champion (3 wins): Keep your Sealed pool (all 17 boosters)\n");
    }
}
