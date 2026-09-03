package forge.game.spellability;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.DelayedReveal;
import forge.game.zone.ZoneType;
import forge.net.HeadlessNetworkGuiGame;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.util.collect.FCollectionView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;

/**
 * A grant clears the restriction zone, so it is the grant that has to reach another player's
 * zone. MayPlayDontGrantZonePermissions marks a cost reduction rather than permission, so on its
 * own it is not enough - but it must not stand in the way when a second grant does allow it.
 */
public class GrantedCastTest extends AITest {

    private Game game;
    private Player me;
    private Player opp;

    @Test
    public void aCostReductionAloneDoesNotReachAnotherHand() {
        newGame();
        Card af = addCard("As Foretold", me);
        af.addCounterInternal(CounterEnumType.TIME, 5, me, false, new forge.game.GameEntityCounterTable(), null);
        addCards("Island", 8, me);
        settle();

        Card mine = addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        Card theirs = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        settle();

        assertEquals("As Foretold still discounts my own card", 2, playable(mine));
        assertEquals("but it must not reach an opponent's hand", 0, playable(theirs));
    }

    @Test
    public void aGrantThatReachesTheZoneStillWorks() {
        newGame();
        Card prize = addCardToZone("Lightning Bolt", opp, ZoneType.Graveyard);
        addCards("Island", 10, me);
        settle();

        resolve(addCardToZone("Mnemonic Betrayal", me, ZoneType.Hand).getFirstSpellAbility(), null);
        assertEquals("Mnemonic Betrayal still casts from their graveyard", 1,
                playable(game.getCardState(prize, prize)));
    }

    @Test
    public void aCostReductionRidesOnTopOfAGrant() {
        newGame();
        Card grenzo = addCard("Grenzo, Crooked Jailer", me);
        addCards("Mountain", 8, me);
        for (int i = 0; i < 6; i++) {
            addCardToZone("Lightning Bolt", opp, ZoneType.Library);
        }
        settle();

        // Heist is what grants the zone; Grenzo's {0} only discounts what it finds there
        resolve(AbilityFactory.getAbility("DB$ Heist | ValidTgts$ Opponent", grenzo), opp);
        Card heisted = opp.getCardsIn(ZoneType.Exile).getFirst();
        assertEquals("Grenzo's discount survives alongside Heist's grant", 2, playable(heisted));
    }

    @Test
    public void aCostReductionGrantedToTheActivePlayerReachesOnlyTheirOwnCards() {
        newGame();
        // the enchantment is theirs, but it grants to whoever is active - that is me
        addCard("Weftwalking", opp);
        addCards("Island", 8, me);
        settle();

        Card mine = addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        Card theirs = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        settle();

        assertEquals("Weftwalking still offers me the free cast of my own card", 2, playable(mine));
        assertEquals("but not of a card I do not own", 0, playable(theirs));
    }

    @Test
    public void stackedUginUltimatePreservesHumanMultiSelectionForItsEffect() {
        newGame();
        Card battlefieldUgin = addCard("Ugin, Eye of the Storms", me);
        addCardToZone("Ugin, Eye of the Storms", me, ZoneType.Library);
        addCardToZone("Kozilek's Command", me, ZoneType.Library);

        PlayerControllerHuman human = new PlayerControllerHuman(game, me, new LobbyPlayerHuman("human")) {
            @Override
            public <T extends GameEntity> List<T> chooseEntitiesForEffect(final FCollectionView<T> optionList,
                    final int min, final int max, final DelayedReveal delayedReveal, final SpellAbility sa,
                    final String title, final Player targetedPlayer, final Map<String, Object> params) {
                final List<T> selected = new ArrayList<>();
                for (T entity : optionList) {
                    selected.add(entity);
                }
                return selected;
            }
        };
        HeadlessNetworkGuiGame gui = new HeadlessNetworkGuiGame();
        gui.setGameView(game.getView());
        gui.setOriginalGameController(me.getView(), human);
        human.setGui(gui);
        me.dangerouslySetController(human);

        SpellAbility ultimate = null;
        for (SpellAbility ability : battlefieldUgin.getSpellAbilities()) {
            if (ability.hasParam("Ultimate")) {
                ultimate = ability;
                break;
            }
        }
        assertFalse("Ugin's ultimate must be present", ultimate == null);
        ultimate.setActivatingPlayer(me);
        game.getStack().add(ultimate);
        game.getStack().resolveStack();

        assertEquals("the human multi-selection moves both cards to exile", 2,
                me.getCardsIn(ZoneType.Exile).size());
        for (Card exiled : me.getCardsIn(ZoneType.Exile)) {
            assertEquals("every card selected through the human stack path receives permission", 1,
                    exiled.mayPlay(me).size());
            assertEquals("every selected card exposes a free-cast ability", 1,
                    exiled.getAllPossibleAbilities(me, true).size());
        }
    }

    private void newGame() {
        game = initAndCreateGame();
        me = game.getPlayers().get(0);
        opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    private void settle() {
        game.getAction().checkStateEffects(true);
    }

    private int playable(Card c) {
        return c.getAllPossibleAbilities(me, true).size();
    }

    private void resolve(SpellAbility sa, Player target) {
        sa.setActivatingPlayer(me);
        sa.resetTargets();
        if (target != null) {
            sa.getTargets().add(target);
        }
        AbilityUtils.resolve(sa);
        settle();
    }
}
