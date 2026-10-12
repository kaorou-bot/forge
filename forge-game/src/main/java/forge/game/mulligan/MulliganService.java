package forge.game.mulligan;

import java.util.List;

import com.google.common.collect.Lists;

import forge.MulliganDefs;
import forge.StaticData;
import forge.game.Game;
import forge.game.GameType;
import forge.game.player.Player;
import forge.game.player.PlayerDecisionFrame;

public class MulliganService {
    Player firstPlayer;
    Game game;
    List<AbstractMulligan> mulligans = Lists.newArrayList();

    public MulliganService(Player player) {
        firstPlayer = player;
        game = firstPlayer.getGame();
    }

    public void perform() {
        initializeMulligans();
        runPlayerMulligans();
        runPostMulligans();
    }

    private void initializeMulligans() {
        List<Player> whoCanMulligan = Lists.newArrayList(game.getPlayers());
        int offset = whoCanMulligan.indexOf(firstPlayer);

        for (int i = 0; i < offset; i++) {
            whoCanMulligan.add(whoCanMulligan.remove(0));
        }

        boolean firstMullFree = game.getPlayers().size() > 2 || game.getRules().hasAppliedVariant(GameType.Brawl);

        for (Player player : whoCanMulligan) {
            MulliganDefs.MulliganRule rule = StaticData.instance().getMulliganRule();
            AbstractMulligan mulligan;
            switch (rule) {
                case Original:
                    mulligan = new OriginalMulligan(player, firstMullFree);
                    break;
                case Paris:
                    mulligan = new ParisMulligan(player, firstMullFree);
                    break;
                case Vancouver:
                    mulligan = new VancouverMulligan(player, firstMullFree);
                    break;
                case London:
                    mulligan = new LondonMulligan(player, firstMullFree);
                    break;
                case Houston:
                    mulligan = new HoustonMulligan(player, firstMullFree);
                    break;
                default:
                    mulligan = new VancouverMulligan(player, firstMullFree);
                    break;
            }

            mulligans.add(mulligan);
            mulligan.beforeFirstMulligan();
        }
    }

    private void runPlayerMulligans() {
        boolean allKept;
        do {
            allKept = true;
            for (AbstractMulligan mulligan : mulligans) {
                if (game.isGameOver()) return;
                if (mulligan.hasKept() || !mulligan.getPlayer().isInGame()) continue;
                if (!firstPlayer.isInGame()) firstPlayer = game.getNextPlayerAfter(firstPlayer);
                if (runPlayerMulligan(mulligan)) allKept = false;
            }
        } while (!allKept);
    }

    /** Returns true only when a surviving player took another mulligan. */
    private boolean runPlayerMulligan(final AbstractMulligan mulligan) {
        final Player p = mulligan.getPlayer();
        final PlayerDecisionFrame frame = PlayerDecisionFrame.enter(PlayerDecisionFrame.Kind.PLAYER_ACTION, p, null);
        try (frame) {
            final boolean keep = !mulligan.canMulligan()
                    || p.getController().mulliganKeepHand(firstPlayer, mulligan.tuckCardsDuringMulligan());
            if (game.isGameOver() || !p.isInGame()) return false;
            if (keep) {
                mulligan.keep();
                return false;
            }
            mulligan.mulligan();
            return p.isInGame();
        } catch (final PlayerDecisionFrame.Aborted aborted) {
            if (!frame.owns(aborted)) throw aborted;
            return false;
        }
    }

    private void runPostMulligans() {
        for (AbstractMulligan mulligan : mulligans) {
            if (game.isGameOver()) return;
            if (!mulligan.getPlayer().isInGame()) continue;
            final PlayerDecisionFrame frame = PlayerDecisionFrame.enter(
                    PlayerDecisionFrame.Kind.PLAYER_ACTION, mulligan.getPlayer(), null);
            try (frame) {
                mulligan.afterMulligan();
            } catch (final PlayerDecisionFrame.Aborted aborted) {
                if (!frame.owns(aborted)) throw aborted;
            }
        }
    }
}
