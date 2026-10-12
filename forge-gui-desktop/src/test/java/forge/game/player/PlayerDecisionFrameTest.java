package forge.game.player;

import forge.game.spellability.SpellAbility;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Test(timeOut = 10000)
public class PlayerDecisionFrameTest {
    public void livePlayerActionCastAndCostCannotBeAbandoned() {
        final Player player = mock(Player.class);
        when(player.isInGame()).thenReturn(true);
        for (final PlayerDecisionFrame.Kind kind : new PlayerDecisionFrame.Kind[] {
                PlayerDecisionFrame.Kind.CAST, PlayerDecisionFrame.Kind.COST,
                PlayerDecisionFrame.Kind.PLAYER_ACTION }) {
            try (PlayerDecisionFrame frame = PlayerDecisionFrame.enter(kind, player, null)) {
                Assert.assertFalse(frame.isAbandoned());
                Assert.expectThrows(IllegalStateException.class, frame::abort);
                when(player.isInGame()).thenReturn(false);
                Assert.assertTrue(frame.isAbandoned());
                Assert.assertTrue(frame.owns(Assert.expectThrows(PlayerDecisionFrame.Aborted.class, frame::abort)));
                when(player.isInGame()).thenReturn(true);
            }
        }
        Assert.assertNull(PlayerDecisionFrame.current());
    }

    public void resolveCannotAbortWhileItsOriginalStackObjectStillExists() {
        final forge.game.Game game = mock(forge.game.Game.class);
        final forge.game.zone.MagicStack stack = mock(forge.game.zone.MagicStack.class);
        final Player player = mock(Player.class);
        final SpellAbility ability = mock(SpellAbility.class);
        final forge.game.spellability.SpellAbilityStackInstance instance =
                mock(forge.game.spellability.SpellAbilityStackInstance.class);
        when(player.getGame()).thenReturn(game);
        when(game.getStack()).thenReturn(stack);
        when(ability.getRootAbility()).thenReturn(ability);
        when(stack.getInstanceMatchingSpellAbilityID(ability)).thenReturn(instance);
        // Even a departed original controller does not prove that this object disappeared.
        when(player.isInGame()).thenReturn(false);
        try (PlayerDecisionFrame frame = PlayerDecisionFrame.enter(PlayerDecisionFrame.Kind.RESOLVE, player, ability)) {
            Assert.assertFalse(frame.isAbandoned());
            Assert.expectThrows(IllegalStateException.class, frame::abort);
            when(stack.getInstanceMatchingSpellAbilityID(ability)).thenReturn(null);
            Assert.assertTrue(frame.isAbandoned());
            Assert.assertTrue(frame.owns(Assert.expectThrows(PlayerDecisionFrame.Aborted.class, frame::abort)));
        }
    }

    public void nestedFramesOnlyOwnTheirExactAbortAndRestoreTheirParent() {
        Assert.assertNull(PlayerDecisionFrame.current());
        final Player player = mock(Player.class);
        final SpellAbility ability = mock(SpellAbility.class);
        try (PlayerDecisionFrame cast = PlayerDecisionFrame.enter(PlayerDecisionFrame.Kind.CAST, player, ability)) {
            Assert.assertSame(cast.player(), player);
            Assert.assertSame(cast.ability(), ability);
            try (PlayerDecisionFrame cost = PlayerDecisionFrame.enter(PlayerDecisionFrame.Kind.COST, player, ability)) {
                final PlayerDecisionFrame.Aborted own = Assert.expectThrows(PlayerDecisionFrame.Aborted.class, cost::abort);
                Assert.assertTrue(cost.owns(own));
                Assert.assertFalse(cast.owns(own));
                final PlayerDecisionFrame.Aborted parent = Assert.expectThrows(PlayerDecisionFrame.Aborted.class, cast::abort);
                Assert.assertTrue(cast.owns(parent));
                Assert.assertFalse(cost.owns(parent));
                Assert.assertSame(PlayerDecisionFrame.current(), cost);
            }
            Assert.assertSame(PlayerDecisionFrame.current(), cast);
        }
        Assert.assertNull(PlayerDecisionFrame.current());
    }

    public void anotherThreadCannotAbortOrCloseTheRulesFrame() throws Exception {
        try (PlayerDecisionFrame frame = PlayerDecisionFrame.enter(PlayerDecisionFrame.Kind.RESOLVE, mock(Player.class), null)) {
            final FutureTask<Void> task = new FutureTask<>(() -> {
                Assert.assertNull(PlayerDecisionFrame.current());
                Assert.expectThrows(IllegalStateException.class, frame::abort);
                Assert.expectThrows(IllegalStateException.class, frame::close);
                return null;
            });
            final Thread worker = new Thread(task, "foreign-decision-frame-test");
            worker.setDaemon(true);
            worker.start();
            try { task.get(3, TimeUnit.SECONDS); }
            finally { task.cancel(true); worker.join(1000); }
            Assert.assertSame(PlayerDecisionFrame.current(), frame);
        }
    }

    public void outOfOrderCloseDoesNotCorruptStackAndClosedFrameCannotAbort() {
        final PlayerDecisionFrame outer = PlayerDecisionFrame.enter(PlayerDecisionFrame.Kind.CAST, mock(Player.class), null);
        try {
            try (PlayerDecisionFrame inner = PlayerDecisionFrame.enter(PlayerDecisionFrame.Kind.COST, mock(Player.class), null)) {
                Assert.expectThrows(IllegalStateException.class, outer::close);
                Assert.assertSame(PlayerDecisionFrame.current(), inner);
            }
        } finally { outer.close(); }
        outer.close();
        Assert.expectThrows(IllegalStateException.class, outer::abort);
        Assert.assertNull(PlayerDecisionFrame.current());
    }
}
