package forge.game.ability.effects;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.StackDescriptionTerms;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.localinstance.properties.ForgeConstants;
import forge.util.CardTranslation;
import forge.util.Localizer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/** Exercise real effect descriptions, not just resource strings. Both UIs consume StackItemView. */
public class StackDescriptionLocalizationTest extends AITest {
    private void language(String language) {
        Localizer.getInstance().setLanguage(language, ForgeConstants.LANG_DIR);
        CardTranslation.preloadTranslation(language, ForgeConstants.LANG_DIR);
    }

    @AfterMethod
    public void restoreEnglish() {
        language("en-US");
    }

    private SpellAbility ability(Game game, String script) {
        Player player = game.getPlayers().get(1);
        player.setName("Jacinta");
        Card host = addCard("Dark Ritual", player);
        SpellAbility sa = AbilityFactory.getAbility(script, host);
        sa.setActivatingPlayer(player);
        return sa;
    }

    @Test
    public void darkRitualUsesChineseInTheSharedStackView() {
        Game game = initAndCreateGame();
        language("zh-CN");
        Card card = addCard("Dark Ritual", game.getPlayers().get(1));
        Player player = card.getController();
        player.setName("Jacinta");
        SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(player);
        String description = sa.getStackDescription();
        assertTrue(description.contains("Jacinta 加 {B}{B}{B}。"), description);
        assertFalse(description.contains("adds"), description);
        assertEquals(new SpellAbilityStackInstance(sa).getView().getText(), description);
    }

    @DataProvider
    public Object[][] playerEffects() {
        return new Object[][] {
            {"DB$ Mana | Defined$ You | Produced$ B | Amount$ 3", "Jacinta adds {B}{B}{B}.", "Jacinta 加 {B}{B}{B}。"},
            {"DB$ Mana | Defined$ You | Produced$ Any | Amount$ 1", "Jacinta adds mana.", "Jacinta 加 法术力。"},
            {"DB$ Draw | Defined$ You | NumCards$ 1", "Jacinta draws a card.", "Jacinta 抓 一张牌。"},
            {"DB$ Draw | Defined$ You | NumCards$ 7", "Jacinta draws seven cards.", "Jacinta 抓 7 张牌。"},
            {"DB$ GainLife | Defined$ You | LifeAmount$ 12", "Jacinta gains 12 life.", "Jacinta 获得 12 点生命。"},
            {"DB$ LoseLife | Defined$ You | LifeAmount$ 4", "Jacinta loses 4 life.", "Jacinta 失去 4 点生命。"},
            {"DB$ Scry | Defined$ You | ScryNum$ 2", "Jacinta scries 2.", "Jacinta 占卜 2。"},
            {"DB$ Surveil | Defined$ You | Amount$ 3", "Jacinta surveils (3).", "Jacinta 刺探 3。"},
            {"DB$ Mill | Defined$ You | NumCards$ 3", "Jacinta mills three cards.", "Jacinta 磨3 张牌。"},
            {"DB$ Mill | Defined$ You | NumCards$ 2 | Optional$ True", "Jacinta may mill two cards.", "Jacinta 可以磨2 张牌。"},
            {"DB$ Discard | Defined$ You | Mode$ TgtChoose | NumCards$ 2", "Jacinta discards two cards.", "Jacinta 弃掉2 张牌。"},
            {"DB$ Discard | Defined$ You | Mode$ Random | NumCards$ 1", "Jacinta discards a card at random.", "Jacinta 随机弃掉一张牌。"},
            {"DB$ Discard | Defined$ You | Mode$ Hand", "Jacinta discards their hand.", "Jacinta 弃掉所有手牌。"}
        };
    }

    @Test(dataProvider = "playerEffects")
    public void generatedEffectsPreserveEnglishAndLocalizeChinese(String script, String english, String chinese) {
        Game game = initAndCreateGame();
        language("en-US");
        SpellAbility sa = ability(game, script);
        String originalParams = sa.getMapParams().toString();
        String en = sa.getStackDescription().trim();
        assertEquals(en, english);
        language("zh-CN");
        String zh = sa.getStackDescription().trim();
        assertEquals(zh, chinese);
        assertEquals(sa.getMapParams().toString(), originalParams, "Localization must not alter card scripts");
        language("en-US");
        assertEquals(sa.getStackDescription().trim(), english, "No cached Chinese text in English mode");
    }

    @Test
    public void playerNamesAreNeverSubjectToTranslationOrFormatting() {
        Game game = initAndCreateGame();
        language("zh-CN");
        SpellAbility sa = ability(game, "DB$ Mana | Defined$ You | Produced$ B | Amount$ 3");
        sa.getActivatingPlayer().setName("O'Brien adds {G} 玩家");
        assertEquals(sa.getStackDescription().trim(), "O'Brien adds {G} 玩家 加 {B}{B}{B}。");
    }

    @Test
    public void multiplePlayersAndSubAbilitiesKeepTheirAmounts() {
        Game game = initAndCreateGame();
        language("zh-CN");
        SpellAbility sa = ability(game, "DB$ Draw | Defined$ Player | NumCards$ 2");
        game.getPlayers().get(0).setName("Alice");
        assertTrue(sa.getStackDescription().contains("各抓 2 张牌"), sa.getStackDescription());
        assertTrue(sa.getStackDescription().contains(" 和 "), sa.getStackDescription());
        Card opt = addCard("Opt", sa.getActivatingPlayer());
        SpellAbility optAbility = opt.getFirstSpellAbility();
        optAbility.setActivatingPlayer(sa.getActivatingPlayer());
        String desc = optAbility.getStackDescription();
        assertTrue(desc.contains("占卜 1"), desc);
        assertTrue(desc.contains("抓 一张牌"), desc);
    }

    @Test
    public void pluralEnglishAndYouGrammarRemainIntact() {
        Game game = initAndCreateGame();
        language("en-US");
        SpellAbility sa = ability(game, "DB$ Draw | Defined$ You | NumCards$ 2");
        sa.getActivatingPlayer().setName("You");
        assertEquals(sa.getStackDescription().trim(), "You draw two cards.");
        SpellAbility all = ability(game, "DB$ Draw | Defined$ Player | NumCards$ 2");
        assertTrue(all.getStackDescription().contains("each draw two cards."), all.getStackDescription());
        language("zh-CN");
        assertTrue(all.getStackDescription().contains("各抓 2 张牌"), all.getStackDescription());
    }

    @Test
    public void scriptConditionsAndUnknownAmountDescriptionsAreNotDiscarded() {
        Game game = initAndCreateGame();
        language("zh-CN");
        SpellAbility draw = ability(game, "DB$ Draw | Defined$ You | NumCards$ 2"
                + " | IfDesc$ If a special condition holds, | NumCardsDesc$ cards equal to a custom value");
        String desc = draw.getStackDescription();
        assertTrue(desc.contains("If a special condition holds,"), desc);
        assertTrue(desc.contains("cards equal to a custom value"), desc);
        SpellAbility mana = ability(game, "DB$ Mana | Defined$ You | Produced$ B | Amount$ 1"
                + " | RestrictValid$ Creature"
                + " | SpellDescription$ Add {B}. Spend this mana only to cast creature spells.");
        assertTrue(mana.getStackDescription().contains("此法术力只能用来施放生物咒语。"),
                mana.getStackDescription());
        mana.setDescription("Add {B}. Spend this mana only on an unknown custom condition.");
        assertTrue(mana.getStackDescription().contains("Spend this mana only on an unknown custom condition."),
                mana.getStackDescription());
    }

    @Test
    public void dividedDamageKeepsEveryAssignment() {
        Game game = initAndCreateGame();
        language("zh-CN");
        SpellAbility sa = ability(game, "DB$ DealDamage | ValidTgts$ Any | NumDmg$ 5"
                + " | DividedAsYouChoose$ True | TargetMax$ 2");
        Player first = game.getPlayers().get(0);
        Player second = game.getPlayers().get(1);
        sa.getTargets().add(first);
        sa.getTargets().add(second);
        sa.addDividedAllocation(first, 2);
        sa.addDividedAllocation(second, 3);
        String desc = sa.getStackDescription();
        assertTrue(desc.contains("造成 5 点伤害"), desc);
        assertTrue(desc.contains("分配给"), desc);
        assertTrue(desc.contains(first + "（2 点伤害）"), desc);
        assertTrue(desc.contains(second + "（3 点伤害）"), desc);
    }

    @Test
    public void damageKeepsSourceTargetAndAmount() {
        Game game = initAndCreateGame();
        language("zh-CN");
        SpellAbility sa = ability(game, "DB$ DealDamage | ValidTgts$ Any | NumDmg$ 3");
        Player target = game.getPlayers().get(0);
        target.setName("Opponent {W}");
        sa.getTargets().add(target);
        String desc = sa.getStackDescription();
        assertTrue(desc.contains("造成 3 点伤害"), desc);
        assertTrue(desc.contains("Opponent {W}"), desc);
        assertTrue(desc.contains(sa.getHostCard().toString()), desc);
    }

    @Test
    public void counterRetainsUnlessCostAndAbilityQualifier() {
        Game game = initAndCreateGame();
        language("zh-CN");
        SpellAbility target = ability(game, "AB$ Draw | Cost$ 1 | Defined$ You | NumCards$ 1");
        SpellAbility counter = ability(game, "DB$ Counter | ValidTgts$ SpellAbility | UnlessCost$ 2");
        counter.getTargets().add(target);
        String desc = counter.getStackDescription();
        assertTrue(desc.contains("反击"), desc);
        assertTrue(desc.contains("的异能"), desc);
        assertTrue(desc.contains("除非其操控者支付 {2}"), desc);
    }

    @Test
    public void cardActionsRetainCardIdsAndRestrictions() {
        Game game = initAndCreateGame();
        language("zh-CN");
        Card target = addCard("Llanowar Elves", game.getPlayers().get(0));
        for (String[] action : new String[][] {
                {"Tap", "横置"}, {"Untap", "重置"}, {"Destroy", "消灭"}
        }) {
            SpellAbility sa = ability(game, "DB$ " + action[0] + " | ValidTgts$ Creature | NoRegen$ True");
            sa.getTargets().add(target);
            String desc = sa.getStackDescription();
            assertTrue(desc.contains(action[1]), desc);
            assertTrue(desc.contains(target.toString()), desc);
            if (action[0].equals("Destroy")) {
                assertTrue(desc.contains("不能重生"), desc);
            }
        }
    }

    @DataProvider
    public Object[][] zoneDescriptions() {
        return new Object[][] {
            {"Battlefield", "Hand", "", "移回其拥有者的手上"},
            {"Graveyard", "Hand", "", "从坟墓场移回其拥有者的手上"},
            {"Exile", "Hand", "", "置于其拥有者的手上"},
            {"Graveyard", "Battlefield", " | Tapped$ True | Attacking$ True | GainControl$ True",
                    "从坟墓场移回战场，且已横置并正进行攻击，由你操控"},
            {"Exile", "Battlefield", "", "放进战场"},
            {"Battlefield", "Library", " | LibraryPosition$ -1", "牌库底"},
            {"Graveyard", "Library", " | LibraryPosition$ 0", "牌库顶"},
            {"Battlefield", "Library", " | LibraryPosition$ 2", "牌库顶起第 3 张的位置"},
            {"Battlefield", "Library", " | Shuffle$ True", "洗入其拥有者的牌库"},
            {"Battlefield", "Exile", " | ExileFaceDown$ True", "牌面朝下地放逐"},
            {"Battlefield", "Graveyard", "", "从战场置入其拥有者的坟墓场"},
            {"Battlefield", "Ante", "", "加入赌注"}
        };
    }

    @Test(dataProvider = "zoneDescriptions")
    public void zoneChangesRetainNamesPositionsAndEntryConditions(
            String origin, String destination, String extra, String expected) {
        Game game = initAndCreateGame();
        language("zh-CN");
        SpellAbility sa = ability(game, "DB$ ChangeZone | ValidTgts$ Creature | Origin$ " + origin
                + " | Destination$ " + destination + extra);
        Card target = addCard("Llanowar Elves", game.getPlayers().get(0));
        sa.getTargets().add(target);
        String params = sa.getMapParams().toString();
        String desc = sa.getStackDescription();
        assertTrue(desc.contains(expected), desc);
        assertTrue(desc.contains(target.toString()), desc);
        assertEquals(sa.getMapParams().toString(), params);
        language("en-US");
        assertFalse(sa.getStackDescription().contains("其拥有者"), sa.getStackDescription());
    }

    @Test
    public void pumpRetainsStatsKeywordsAndDuration() {
        Game game = initAndCreateGame();
        language("zh-CN");
        Card target = addCard("Llanowar Elves", game.getPlayers().get(0));
        SpellAbility sa = ability(game, "AB$ Pump | Cost$ 0 | ValidTgts$ Creature | NumAtt$ 3 | NumDef$ -2"
                + " | KW$ Flying & First strike | CanBlockAmount$ 2");
        sa.getTargets().add(target);
        String desc = sa.getStackDescription();
        assertTrue(desc.contains(target.toString()), desc);
        assertTrue(desc.contains("+3/-2"), desc);
        assertTrue(desc.contains("飞行") && desc.contains("先攻"), desc);
        assertTrue(desc.contains("额外阻挡 2 个生物"), desc);
        assertTrue(desc.contains("直到回合结束"), desc);
        sa.putParam("Duration", "UntilUntaps");
        assertTrue(sa.getStackDescription().contains("持续为横置状态"), sa.getStackDescription());
        sa.putParam("Duration", "Permanent");
        assertFalse(sa.getStackDescription().contains("直到回合结束"), sa.getStackDescription());
        sa.putParam("KW", "Unknown keyword: do not erase {U}");
        assertTrue(sa.getStackDescription().contains("Unknown keyword: do not erase {U}"), sa.getStackDescription());
    }

    @Test
    public void optionalSacrificeAndUntapUpToKeepTheAmounts() {
        Game game = initAndCreateGame();
        language("zh-CN");
        SpellAbility sa = ability(game, "DB$ Sacrifice | Defined$ You | SacValid$ Creature | Amount$ 2 | Optional$ True");
        String desc = sa.getStackDescription();
        assertTrue(desc.contains("（可选）"), desc);
        assertTrue(desc.contains("牺牲 2 个生物"), desc);
        SpellAbility untap = ability(game, "DB$ Untap | UntapUpTo$ True | Amount$ 2 | UntapType$ artifact");
        assertEquals(untap.getStackDescription().trim(), "重置 至多 2 个神器.");
        assertEquals(StackDescriptionTerms.translate("a creature with an unknown restriction"),
                "a creature with an unknown restriction");
    }

    @Test
    public void simpleTokensKeepNumberStatsColorTypeAndKeywords() {
        Game game = initAndCreateGame();
        language("zh-CN");
        SpellAbility sa = ability(game, "DB$ Token | TokenAmount$ 3 | TokenOwner$ You | TokenScript$ w_1_1_soldier"
                + " | SpellDescription$ Create three 1/1 white Soldier creature tokens with flying and vigilance.");
        String desc = sa.getStackDescription();
        assertTrue(desc.contains("Jacinta 派出 3 个1/1白色士兵生物衍生物"), desc);
        assertTrue(desc.contains("飞行 和 警戒"), desc);
        // Formula amounts must not be replaced with the static count from the printed oracle.
        sa.putParam("TokenAmount", "X");
        sa.setSVar("X", "Number$5");
        assertTrue(sa.getStackDescription().contains("派出 5 个"), sa.getStackDescription());
        sa.setSVar("X", "Number$0");
        assertTrue(sa.getStackDescription().contains("creates"), sa.getStackDescription());
        // Unknown token characteristics keep the entire original description, not a partial guess.
        sa.putParam("SpellDescription", "Create three 1/1 white CustomType creature tokens with an unknown restriction.");
        assertTrue(sa.getStackDescription().contains("CustomType"), sa.getStackDescription());
        assertTrue(sa.getStackDescription().contains("unknown restriction"), sa.getStackDescription());
    }

    @Test
    public void noUntapAndRadianceKeepTheirConditions() {
        Game game = initAndCreateGame();
        language("zh-CN");
        Card target = addCard("Llanowar Elves", game.getPlayers().get(0));
        SpellAbility pump = ability(game, "DB$ Pump | ValidTgts$ Creature"
                + " | KW$ HIDDEN This card doesn't untap during your next untap step.");
        pump.getTargets().add(target);
        String desc = pump.getStackDescription();
        assertTrue(desc.contains("其操控者的下一个重置步骤中不能重置"), desc);
        SpellAbility destroy = ability(game, "DB$ Destroy | ValidTgts$ Creature | Radiance$ True");
        destroy.getTargets().add(target);
        assertTrue(destroy.getStackDescription().contains("与它有共通颜色的其他生物"),
                destroy.getStackDescription());
    }
}
