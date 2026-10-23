package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.MobileTestEnvironment;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.AdventureQuestStage;
import forge.adventure.data.DialogData;
import forge.adventure.player.AdventurePlayer;
import forge.util.Localizer;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Dictionary;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.testng.Assert.*;
import static org.mockito.Mockito.*;

public class AdventureQuestLocalizationTest {
    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.isDirectory(path.resolve("forge-gui/res"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Cannot locate resources");
    }

    private static FileHandle overlay() {
        return new FileHandle(root().resolve(
                "forge-gui/res/adventure/Shandalar/languages/quest-text-zh-CN.json").toFile());
    }

    @BeforeClass
    public void initialize() throws Exception {
        MobileTestEnvironment.initialize();
        Localizer.getInstance().setLanguage("en-US", root().resolve("forge-gui/res/languages").toString());
    }

    @SuppressWarnings("unchecked")
    private static Array<AdventureQuestData> quests() {
        return new Json().fromJson(Array.class, AdventureQuestData.class, new FileHandle(root().resolve(
                "forge-gui/res/adventure/Shandalar/world/quests.json").toFile()));
    }

    private static AdventureQuestData quest(final Array<AdventureQuestData> quests, final int id) {
        for (AdventureQuestData quest : quests) {
            if (quest.getID() == id) {
                return quest;
            }
        }
        throw new AssertionError("Quest not found: " + id);
    }

    private static void removeDisplayText(final JsonValue value) {
        if (value.isObject()) {
            value.remove("name");
            value.remove("description");
            value.remove("text");
            value.remove("rewardDescription");
        }
        for (JsonValue child = value.child; child != null; child = child.next) {
            removeDisplayText(child);
        }
    }

    private static String nonDisplaySnapshot(final Array<AdventureQuestData> quests) {
        final JsonValue value = new JsonReader().parse(new Json().toJson(quests));
        removeDisplayText(value);
        return value.toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void translatesRealTemplatesWithoutChangingRulesOrOtherQuestContexts() throws Exception {
        final Array<AdventureQuestData> quests = quests();
        final AdventureQuestData custom = new Json().fromJson(AdventureQuestData.class,
                "{id:10001,name:Travel,description:\"Custom quest\"}");
        quests.add(custom);
        final String before = nonDisplaySnapshot(quests);
        final String unrelated = new Json().toJson(custom);
        AdventureQuestLocalization.apply(quests, overlay());
        assertEquals(quest(quests, 1).name, "讨伐之约");
        assertEquals(quest(quests, 2).name, "远行送信");
        assertEquals(quest(quests, 3).name, "开张在即");
        assertEquals(quest(quests, 4).name, "追猎");
        assertEquals(quest(quests, 5).name, "预定葬礼");
        assertEquals(quest(quests, 6).name, "高原上的正义");
        assertEquals(quest(quests, 7).name, "圣沙之地");
        assertEquals(quest(quests, 8).name, "远程授课");
        assertEquals(quest(quests, 9).name, "荒地追凶");
        assertEquals(quest(quests, 10).name, "为新生腾出空间");
        assertEquals(quest(quests, 11).name, "逐客令");
        assertEquals(quest(quests, 44).name, "寻找商队");
        assertEquals(quest(quests, 52).name, "敌人的敌人……");
        assertEquals(quest(quests, 53).name, "初到山德拉");
        assertTrue(quest(quests, 1).description.contains("$(enemy_1)"));
        assertTrue(quest(quests, 2).offerDialog.text.contains("$(poi_1)"));
        assertTrue(quest(quests, 2).offerDialog.text.contains("$(biome_1)"));
        assertEquals(nonDisplaySnapshot(quests), before);
        assertEquals(new Json().toJson(custom), unrelated);
        // The production controller copies templates before substituting dynamic tokens.
        final AdventureQuestData copy = new AdventureQuestData(quest(quests, 1));
        final Field tokensField = AdventureQuestData.class.getDeclaredField("otherTokens");
        tokensField.setAccessible(true);
        ((Dictionary<String, String>) tokensField.get(copy)).put("$(enemy_1)", "敌人 O'Brien {G}");
        copy.replaceTokens();
        assertTrue(copy.description.contains("敌人 O'Brien {G}"));
        assertFalse(copy.description.contains("$(enemy_1)"));
        assertTrue(quest(quests, 1).description.contains("$(enemy_1)"));
    }

    @Test
    public void missingMalformedAndDuplicateOverlaysLeaveOriginalTextUntouched() throws Exception {
        final Array<AdventureQuestData> quests = quests();
        final String before = new Json().toJson(quests);
        final Path directory = Files.createTempDirectory("forge-quest-localization-");
        final Path file = directory.resolve("invalid.json");
        try {
            AdventureQuestLocalization.apply(quests, new FileHandle(file.toFile()));
            assertEquals(new Json().toJson(quests), before);
            for (String invalid : new String[] {"{", "{}",
                    "[{questId:1,source:Travel,translation:赶路},{questId:1,source:Travel,translation:重复}]"}) {
                Files.writeString(file, invalid, StandardCharsets.UTF_8);
                AdventureQuestLocalization.apply(quests, new FileHandle(file.toFile()));
                assertEquals(new Json().toJson(quests), before);
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void changedUpstreamTextAndExplicitLocaleKeysAreNotOverridden() {
        final Array<AdventureQuestData> quests = quests();
        final AdventureQuestData first = quest(quests, 1);
        first.name = "Changed upstream title";
        first.offerDialog.loctext = "existing.locale.key";
        final String original = first.offerDialog.text;
        final DialogData choice = first.offerDialog.options[0];
        choice.locname = "existing.option.key";
        final String choiceName = choice.name;
        AdventureQuestLocalization.apply(quests, overlay());
        assertEquals(first.name, "Changed upstream title");
        assertEquals(first.offerDialog.text, original);
        assertEquals(choice.name, choiceName);
    }

    private static Map<String, Integer> tokens(final String value) {
        final Map<String, Integer> tokens = new TreeMap<>();
        final Matcher matcher = Pattern.compile("\\$\\([^)]+\\)").matcher(value);
        while (matcher.find()) {
            tokens.merge(matcher.group(), 1, Integer::sum);
        }
        return tokens;
    }

    private static List<String> markup(final String text) {
        final List<String> result = new ArrayList<>();
        final Matcher matcher = Pattern.compile("\\{[^{}]+\\}|\\[(?:/|i)?\\]").matcher(text);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void dialogueEffectsRetainTagOrderAndSurviveTemplateTokenSubstitution() throws Exception {
        final JsonValue entries = new JsonReader().parse(overlay());
        int taggedEntries = 0;
        for (JsonValue entry = entries.child; entry != null; entry = entry.next) {
            final String source = entry.getString("source");
            final List<String> tags = markup(source);
            assertEquals(markup(entry.getString("translation")), tags, source);
            if (!tags.isEmpty()) {
                taggedEntries++;
            }
        }
        assertEquals(taggedEntries, 11);
        final Array<AdventureQuestData> templates = quests();
        AdventureQuestLocalization.apply(templates, overlay());
        final AdventureQuestData copy = new AdventureQuestData(quest(templates, 9));
        final Field tokensField = AdventureQuestData.class.getDeclaredField("otherTokens");
        tokensField.setAccessible(true);
        ((Dictionary<String, String>) tokensField.get(copy)).put("$(enemy_2)", "目标甲");
        copy.replaceTokens();
        assertTrue(copy.description.contains("目标甲"));
        final Set<String> dialogue = new HashSet<>();
        dialogText(dialogue, copy.offerDialog);
        assertTrue(dialogue.contains("{RAINBOW=2;2;1;0.8}山德拉马戏团，即将巡演各地！！！！"));
        assertTrue(dialogue.stream().anyMatch(text -> text.contains("{COLOR=red}杀掉！{ENDCOLOR}")));
        assertTrue(dialogue.stream().anyMatch(text -> text.contains("{COLOR=red}杀——掉……{ENDCOLOR}")));
        assertFalse(dialogue.stream().anyMatch(text -> text.contains("$(enemy_2)")));
        assertTrue(quest(templates, 9).description.contains("$(enemy_2)"));
    }


    private static void collectQuestIssues(final DialogData dialog, final List<DialogData.ActionData[]> result) {
        if (dialog == null) {
            return;
        }
        for (DialogData.ActionData action : dialog.action) {
            if (action.issueQuest != null && !action.issueQuest.isEmpty()) {
                result.add(dialog.action);
                break;
            }
        }
        for (DialogData option : dialog.options) {
            collectQuestIssues(option, result);
        }
    }

    @Test
    public void bothRemoteInstructionAcceptBranchesKeepTheOfferedQuestInstance() {
        final Array<AdventureQuestData> templates = quests();
        for (boolean translated : new boolean[] {false, true}) {
            if (translated) {
                AdventureQuestLocalization.apply(templates, overlay());
            }
            final AdventureQuestData offered = new AdventureQuestData(quest(templates, 8));
            offered.sourceID = "original-town";
            final List<DialogData.ActionData[]> branches = new ArrayList<>();
            collectQuestIssues(offered.offerDialog, branches);
            assertEquals(branches.size(), 2);
            for (DialogData.ActionData[] actions : branches) {
                final AdventurePlayer player = mock(AdventurePlayer.class);
                try (var current = mockStatic(Current.class)) {
                    current.when(Current::player).thenReturn(player);
                    final MapDialog dialog = new MapDialog(offered.offerDialog, null, -1, offered);
                    dialog.setEffects(actions);
                    assertEquals(dialog.questAccepted, "8");
                    // Exercise the production acceptance callback: use the offered template
                    // instance (with its original targets), not a newly looked-up quest 7.
                    verify(player).addQuest(same(offered), eq(false));
                    verify(player, never()).addQuest(anyString(), anyBoolean());
                    assertEquals(offered.sourceID, "original-town");
                }
            }
        }
    }

    @Test
    public void contextualExitLabelsDoNotSendDungeonPlayersBackToTown() {
        final Array<AdventureQuestData> templates = quests();
        AdventureQuestLocalization.apply(templates, overlay());
        for (int id : new int[] {11, 47, 48}) {
            assertTrue(java.util.Arrays.stream(quest(templates, id).stages)
                    .anyMatch(stage -> "离开".equals(stage.name)), "Quest " + id);
        }
        assertEquals(quest(templates, 12).stages[1].name, "出城");
    }

    private static List<String> signedValues(final String text) {
        final List<String> result = new ArrayList<>();
        final Matcher matcher = Pattern.compile("[+-][0-9]+").matcher(text);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    @Test
    public void reputationAndRewardSignsRemainUnchanged() {
        final JsonValue entries = new JsonReader().parse(overlay());
        for (JsonValue entry = entries.child; entry != null; entry = entry.next) {
            final String source = entry.getString("source");
            assertEquals(signedValues(entry.getString("translation")), signedValues(source), source);
        }
    }
    private static void addText(final Set<String> texts, final String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                texts.add(value);
            }
        }
    }

    private static void dialogText(final Set<String> texts, final DialogData dialog) {
        if (dialog == null) {
            return;
        }
        if (dialog.locname == null || dialog.locname.isEmpty()) {
            addText(texts, dialog.name);
        }
        if (dialog.loctext == null || dialog.loctext.isEmpty()) {
            addText(texts, dialog.text);
        }
        for (DialogData option : dialog.options) {
            dialogText(texts, option);
        }
    }

    @Test
    public void allShandalarQuestsCoverAllDisplayBranchesIncludingFailureAndDecline() {
        final JsonValue entries = new JsonReader().parse(overlay());
        final Array<AdventureQuestData> templates = quests();
        assertEquals(templates.size, 53);
        for (AdventureQuestData quest : templates) {
            final int id = quest.getID();
            final Set<String> expected = new HashSet<>();
            addText(expected, quest.name, quest.description, quest.rewardDescription);
            dialogText(expected, quest.offerDialog);
            dialogText(expected, quest.prologue);
            dialogText(expected, quest.epilogue);
            dialogText(expected, quest.failureDialog);
            dialogText(expected, quest.declinedDialog);
            for (AdventureQuestStage stage : quest.stages) {
                addText(expected, stage.name, stage.description);
                dialogText(expected, stage.prologue);
                dialogText(expected, stage.epilogue);
                dialogText(expected, stage.failureDialog);
            }
            final Set<String> actual = new HashSet<>();
            for (JsonValue entry = entries.child; entry != null; entry = entry.next) {
                if (entry.getInt("questId") == id) {
                    actual.add(entry.getString("source"));
                }
            }
            assertEquals(actual, expected, "Complete display coverage for quest " + id);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void expansionWorldsHaveExactContextCoverageWithoutChangingRules() {
        final String[] worlds = {"Amonkhet", "Crystal_Kingdoms", "Innistrad",
                "Realm of Legends", "Shandalar Old Border"};
        final int[] counts = {23, 53, 52, 53, 55};
        final int[] mappings = {730, 1409, 1428, 1409, 1515};
        for (int w = 0; w < worlds.length; w++) {
            final Path directory = root().resolve("forge-gui/res/adventure").resolve(worlds[w]);
            final Array<AdventureQuestData> templates = new Json().fromJson(Array.class,
                    AdventureQuestData.class, new FileHandle(directory.resolve("world/quests.json").toFile()));
            final FileHandle file = new FileHandle(directory.resolve("languages/quest-text-zh-CN.json").toFile());
            final JsonValue entries = new JsonReader().parse(file);
            assertEquals(templates.size, counts[w], worlds[w]);
            assertEquals(entries.size, mappings[w], worlds[w]);
            final Set<String> unique = new HashSet<>();
            for (JsonValue entry = entries.child; entry != null; entry = entry.next) {
                final String source = entry.getString("source");
                final String translation = entry.getString("translation");
                assertTrue(unique.add(entry.getInt("questId") + ":" + source), source);
                assertEquals(tokens(translation), tokens(source), source);
                assertEquals(signedValues(translation), signedValues(source), source);
                assertEquals(markup(translation), markup(source), source);
                assertTrue(translation.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), source);
            }
            for (AdventureQuestData quest : templates) {
                final Set<String> expected = new HashSet<>();
                addText(expected, quest.name, quest.description, quest.rewardDescription);
                dialogText(expected, quest.offerDialog);
                dialogText(expected, quest.prologue);
                dialogText(expected, quest.epilogue);
                dialogText(expected, quest.failureDialog);
                dialogText(expected, quest.declinedDialog);
                for (AdventureQuestStage stage : quest.stages) {
                    addText(expected, stage.name, stage.description);
                    dialogText(expected, stage.prologue);
                    dialogText(expected, stage.epilogue);
                    dialogText(expected, stage.failureDialog);
                }
                final Set<String> actual = new HashSet<>();
                for (JsonValue entry = entries.child; entry != null; entry = entry.next) {
                    if (entry.getInt("questId") == quest.getID()) {
                        actual.add(entry.getString("source"));
                    }
                }
                assertEquals(actual, expected, worlds[w] + " quest " + quest.getID());
            }
            final String rules = nonDisplaySnapshot(templates);
            AdventureQuestLocalization.apply(templates, file);
            assertEquals(nonDisplaySnapshot(templates), rules, worlds[w]);
        }
    }

    @Test
    public void overlayHasUniqueContextKeysAndPreservesEveryDynamicToken() {
        final JsonValue entries = new JsonReader().parse(overlay());
        final Array<AdventureQuestData> quests = quests();
        final Set<String> unique = new HashSet<>();
        assertEquals(entries.size, 1409);
        for (JsonValue entry = entries.child; entry != null; entry = entry.next) {
            final int id = entry.getInt("questId");
            final String source = entry.getString("source");
            final String translation = entry.getString("translation");
            assertTrue(unique.add(id + ":" + source));
            assertEquals(tokens(translation), tokens(source), source);
            assertTrue(translation.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), source);
            // Verify that no entry was invented or became stale against the actual source.
            final String template = new Json().toJson(quest(quests, id));
            final String quotedSource = new Json().toJson(source);
            assertTrue(template.contains(quotedSource), id + ": " + source);
        }
    }
}
