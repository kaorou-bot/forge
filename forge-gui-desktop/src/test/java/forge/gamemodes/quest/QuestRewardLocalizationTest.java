package forge.gamemodes.quest;

import forge.GuiDesktop;
import forge.LobbyPlayer;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.GameView;
import forge.game.player.GameLossReason;
import forge.game.player.PlayerOutcome;
import forge.game.player.PlayerStatistics;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.quest.bazaar.QuestItemType;
import forge.gamemodes.quest.data.QuestPreferences;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gui.GuiBase;
import forge.gui.interfaces.IButton;
import forge.gui.interfaces.IWinLoseView;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.Localizer;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class QuestRewardLocalizationTest {
    private static Path languages() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.isDirectory(path.resolve("forge-gui/res/languages"))) {
                return path.resolve("forge-gui/res/languages");
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Cannot locate resources");
    }

    private static void language(final String locale) {
        Localizer.getInstance().setEnglish(false);
        Localizer.getInstance().setLanguage(locale, languages().toString());
    }

    @AfterMethod
    public void resetLanguage() {
        language("en-US");
    }

    private static Properties properties(final String locale) throws Exception {
        final Properties result = new Properties();
        try (Reader reader = Files.newBufferedReader(languages().resolve(locale + ".properties"), StandardCharsets.UTF_8)) {
            result.load(reader);
        }
        return result;
    }

    private static Set<String> parameters(final String text) {
        final Set<String> result = new TreeSet<>();
        final Matcher matcher = Pattern.compile("\\{[0-9]+\\}").matcher(text);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    @Test
    public void everyRewardKeyHasMatchingParametersAndFormatsInBothLanguages() throws Exception {
        final Properties english = properties("en-US");
        final Properties chinese = properties("zh-CN");
        int count = 0;
        for (String key : english.stringPropertyNames()) {
            if (!key.startsWith("questReward.")) {
                continue;
            }
            count++;
            assertTrue(chinese.containsKey(key), key);
            assertEquals(parameters(chinese.getProperty(key)), parameters(english.getProperty(key)), key);
            for (String locale : new String[] {"en-US", "zh-CN"}) {
                language(locale);
                final String text = Localizer.getInstance().getMessage(key, "25", "50", "75");
                assertFalse(text.contains("INVALID PROPERTY"), key);
                assertTrue(parameters(text).isEmpty(), key);
                if (locale.equals("zh-CN")) {
                    assertTrue(text.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), key);
                }
            }
        }
        assertEquals(count, 38);
    }

    @Test
    public void milestonePenaltyAndQuestReminderUseActualArguments() {
        language("zh-CN");
        final Localizer localizer = Localizer.getInstance();
        assertEquals(localizer.getMessage("questReward.quitPenalty", 37), "退出（扣除 37 积分）");
        assertTrue(localizer.getMessage("questReward.streakToken", 50).contains("50 连胜"));
        assertFalse(localizer.getMessage("questReward.streakToken", 50).contains("25"));
        assertTrue(localizer.getMessage("questReward.streakToken", 25).contains("25 连胜"));
        assertEquals(localizer.getMessage("questReward.booster.Draft"), "轮抽补充包");
        assertEquals(localizer.getMessageorUseDefault("questReward.booster.Custom", "Custom"), "Custom");
        language("en-US");
        final String reminder = localizer.getMessage("advQuestNotFinished", "O'Brien {G}");
        assertTrue(reminder.contains("haven't"));
        assertTrue(reminder.contains("O'Brien {G}"));
        assertFalse(reminder.contains("{0}"));
    }

    @DataProvider(name = "creditCases")
    public Object[][] creditCases() {
        return new Object[][] {
            {"zh-CN", false, 501}, {"en-US", false, 501},
            {"zh-CN", true, 443}, {"en-US", true, 443}
        };
    }

    @Test(dataProvider = "creditCases")
    @SuppressWarnings("unchecked")
    public void localizedBreakdownDoesNotChangeCredits(final String locale, final boolean lostGame,
                                                       final int expectedCredits) throws Exception {
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(new GuiDesktop());
        }
        language(locale);
        try (var model = mockStatic(FModel.class)) {
            final QuestPreferences preferences = mock(QuestPreferences.class);
            model.when(FModel::getQuestPreferences).thenReturn(preferences);
            model.when(FModel::getPreferences).thenReturn(mock(ForgePreferences.class));
            when(preferences.getPrefDouble(QPref.WILD_OPPONENTS_MULTIPLIER)).thenReturn(2.0);
            when(preferences.getPrefInt(QPref.REWARDS_BASE)).thenReturn(100);
            when(preferences.getPrefInt(QPref.REWARDS_WINS_MULTIPLIER_MAX)).thenReturn(10);
            when(preferences.getPref(QPref.REWARDS_WINS_MULTIPLIER)).thenReturn("2");
            when(preferences.getPrefInt(QPref.REWARDS_POISON)).thenReturn(25);
            when(preferences.getPrefInt(QPref.REWARDS_MULLIGAN0)).thenReturn(10);
            when(preferences.getPrefInt(QPref.REWARDS_TURN5)).thenReturn(30);
            when(preferences.getPrefInt(QPref.REWARDS_HEALTH_DIFF_MAX)).thenReturn(10);
            when(preferences.getPrefInt(QPref.REWARDS_UNDEFEATED)).thenReturn(50);
            final QuestController quest = mock(QuestController.class, RETURNS_DEEP_STUBS);
            model.when(FModel::getQuest).thenReturn(quest);
            when(quest.getCurrentEvent().getDifficulty()).thenReturn(QuestEventDifficulty.MEDIUM);
            when(quest.getCurrentEvent().getIsRandomMatch()).thenReturn(true);
            when(quest.getAchievements().getWin()).thenReturn(20);
            when(quest.getAssets().getItemLevel(QuestItemType.ESTATES)).thenReturn(2);

            final LobbyPlayer human = GamePlayerUtil.getQuestPlayer();
            final RegisteredPlayer humanRegistration = mock(RegisteredPlayer.class);
            when(humanRegistration.getPlayer()).thenReturn(human);
            final RegisteredPlayer opponent = mock(RegisteredPlayer.class);
            when(opponent.getPlayer()).thenReturn(mock(LobbyPlayer.class));
            final PlayerStatistics humanStats = mock(PlayerStatistics.class);
            when(humanStats.getOpeningHandSize()).thenReturn(0);
            final PlayerStatistics opponentStats = mock(PlayerStatistics.class);
            when(opponentStats.getOutcome()).thenReturn(PlayerOutcome.loss(GameLossReason.Poisoned, null));
            final GameOutcome win = mock(GameOutcome.class);
            when(win.isWinner(human)).thenReturn(true);
            when(win.getWinCondition()).thenReturn(GameEndReason.AllOpposingTeamsLost);
            when(win.getLastTurnNumber()).thenReturn(5);
            when(win.getLifeDelta()).thenReturn(50);
            when(win.iterator()).thenAnswer(invocation -> List.of(
                    Map.entry(humanRegistration, humanStats), Map.entry(opponent, opponentStats)).iterator());
            final GameView game = mock(GameView.class);
            when(game.getOutcomesOfMatch()).thenReturn(lostGame
                    ? List.of(win, mock(GameOutcome.class)) : List.of(win));
            final IWinLoseView<IButton> view = mock(IWinLoseView.class);
            final QuestWinLoseController controller = new QuestWinLoseController(game, view);
            final Method award = QuestWinLoseController.class.getDeclaredMethod("awardEventCredits");
            award.setAccessible(true);
            award.invoke(controller);

            // 150 base doubled + 20 prior wins + 25 poison + 10 mulligan + 30 turn + 1 life.
            // Add 50 only if undefeated, then apply 15% estates with the original truncation.
            verify(quest.getAssets()).addCredits(expectedCredits);
            final ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(view).showMessage(message.capture(), anyString(), eq(FSkinProp.ICO_QUEST_GOLD));
            final String text = message.getValue();
            assertTrue(text.contains(String.valueOf(expectedCredits)), text);
            assertFalse(text.contains("INVALID PROPERTY"), text);
            assertTrue(parameters(text).isEmpty(), text);
            if (locale.equals("zh-CN")) {
                assertTrue(text.contains("中等难度"), text);
                assertTrue(text.contains("中毒"), text);
                assertTrue(text.contains("15%"), text);
                assertEquals(text.contains("保持全胜"), !lostGame, text);
                assertFalse(text.contains("Bonus"), text);
                assertFalse(text.contains("credits"), text);
            }
        }
    }
}
