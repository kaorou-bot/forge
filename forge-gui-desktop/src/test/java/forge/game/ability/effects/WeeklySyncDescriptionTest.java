package forge.game.ability.effects;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.localinstance.properties.ForgeConstants;
import forge.util.CardTranslation;
import forge.util.Localizer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/** Regression coverage for the November 17 effect-description merge. */
public class WeeklySyncDescriptionTest extends AITest {
    private void language(String locale) {
        Localizer.getInstance().setLanguage(locale, ForgeConstants.LANG_DIR);
        CardTranslation.preloadTranslation(locale, ForgeConstants.LANG_DIR);
    }

    @AfterMethod public void restoreEnglish() { language("en-US"); }

    private SpellAbility ability(Game game, String script) {
        Player player = game.getPlayers().get(1);
        player.setName("Jacinta");
        Card host = addCard("Dark Ritual", player);
        SpellAbility sa = AbilityFactory.getAbility(script, host);
        sa.setActivatingPlayer(player);
        return sa;
    }

    @DataProvider public Object[][] fallbackTokens() {
        return new Object[][] {
            {"c:Equipped", "equipped creature", "所装备的生物"},
            {"c:Enchanted", "enchanted permanent", "所结附的永久物"}
        };
    }

    @Test(dataProvider = "fallbackTokens")
    public void missingAttachmentsHaveLocalizedNames(String token, String english, String chinese) {
        Game game = initAndCreateGame();
        SpellAbility sa = ability(game, "AB$ Pump | Cost$ 0 | StackDescription$ {" + token + "}");
        language("en-US");
        String en = sa.getStackDescription();
        assertTrue(en.toLowerCase().contains(english), en);
        language("zh-CN");
        String zh = sa.getStackDescription();
        assertTrue(zh.contains(chinese), zh);
        assertFalse(zh.contains("{" + token + "}"), zh);
        language("en-US");
        assertEquals(sa.getStackDescription(), en);
    }

    @Test public void optionalSacrificeKeepsChoiceAndDeferredQuantity() {
        Game game = initAndCreateGame();
        SpellAbility sa = ability(game, "DB$ Sacrifice | Defined$ You | SacValid$ Creature | Amount$ X | Optional$ True");
        sa.setSVar("X", "Count$RememberedSize");
        String params = sa.getMapParams().toString();
        language("en-US");
        String en = sa.getStackDescription();
        assertTrue(en.contains("may sacrifice"), en);
        assertFalse(en.contains("zero creatures"), en);
        language("zh-CN");
        String zh = sa.getStackDescription();
        assertTrue(zh.contains("可以牺牲") && zh.contains("任意数量"), zh);
        assertFalse(zh.contains("sacrifice"), zh);
        assertEquals(sa.getMapParams().toString(), params);
        assertEquals(sa.getSVar("X"), "Count$RememberedSize");
        language("en-US");
        assertEquals(sa.getStackDescription(), en);
    }

    @Test public void pumpKeepsKnownKeywordsAndStatsAcrossLanguageChanges() {
        Game game = initAndCreateGame();
        SpellAbility sa = ability(game, "AB$ Pump | Cost$ 0 | ValidTgts$ Creature | NumAtt$ 2 | NumDef$ 3 | KW$ Flying & Vigilance");
        sa.getTargets().add(addCard("Llanowar Elves", game.getPlayers().get(0)));
        language("en-US");
        String en = sa.getStackDescription();
        assertTrue(en.contains("+2/+3") && en.contains("flying") && en.contains("vigilance"), en);
        language("zh-CN");
        String zh = sa.getStackDescription();
        assertTrue(zh.contains("+2/+3") && zh.contains("飞行") && zh.contains("警戒"), zh);
        language("en-US");
        assertEquals(sa.getStackDescription(), en);
    }

    @Test public void emptyChangeZoneTargetsRemainSafeInBothLanguages() {
        Game game = initAndCreateGame();
        SpellAbility sa = ability(game, "AB$ ChangeZone | Cost$ 0 | ValidTgts$ Creature | Origin$ Battlefield | Destination$ Exile | TargetMin$ 0 | TargetMax$ 1");
        for (String locale : new String[] {"en-US", "zh-CN"}) {
            language(locale);
            String description = sa.getStackDescription();
            assertNotNull(description);
            assertFalse(description.contains("null"), description);
            assertTrue(sa.getTargets().isEmpty());
        }
    }
}
