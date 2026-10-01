package forge.screens.match.layout;

import forge.card.MagicColor;
import forge.game.GameView;
import forge.game.player.PlayerView;

/** Declarative predicates only: a skin never evaluates scripts or reads hidden cards. */
public enum MatchVisibility {
    ALWAYS, MANA_NONEMPTY, STACK_NONEMPTY, STACK_EMPTY, PLAYER_ACTIVE, STATUS_NONEMPTY;

    public boolean test(GameView game, PlayerView player) {
        return switch (this) {
            case ALWAYS -> true;
            case STATUS_NONEMPTY -> player != null && player.getCounters() != null && !player.getCounters().isEmpty();
            case STACK_NONEMPTY -> game != null && !game.getStack().isEmpty();
            case STACK_EMPTY -> game == null || game.getStack().isEmpty();
            case PLAYER_ACTIVE -> game != null && player != null && player.equals(game.getPlayerTurn());
            case MANA_NONEMPTY -> player != null && (player.getMana(MagicColor.WHITE)
                    + player.getMana(MagicColor.BLUE) + player.getMana(MagicColor.BLACK)
                    + player.getMana(MagicColor.RED) + player.getMana(MagicColor.GREEN)
                    + player.getMana(MagicColor.COLORLESS)) > 0;
        };
    }
}
