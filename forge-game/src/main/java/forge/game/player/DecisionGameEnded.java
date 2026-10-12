package forge.game.player;

import forge.game.Game;

/** Ends a synchronous decision chain only after this exact game has an outcome. */
public final class DecisionGameEnded extends RuntimeException implements DecisionWaitCancelled {
    private static final long serialVersionUID = 1L;
    private final Game game;

    public DecisionGameEnded(final Game game) {
        super("The game ended while awaiting a decision");
        if (!game.isGameOver()) throw new IllegalArgumentException("The game has not ended");
        this.game = game;
    }

    public boolean belongsTo(final Game current) { return game == current; }
}
