package forge.game.player;

import forge.game.spellability.SpellAbility;

/**
 * A structured cancellation boundary on the original rules call stack. This class does
 * not schedule work, concede a player, or invent a choice. In particular a COST abort
 * is not an empty answer to a mandatory choice made while resolving somebody's spell.
 */
public final class PlayerDecisionFrame implements AutoCloseable {
    public enum Kind { CAST, COST, RESOLVE, PLAYER_ACTION }

    public static final class Aborted extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final PlayerDecisionFrame frame;
        private Aborted(final PlayerDecisionFrame frame) {
            super("Player decision abandoned: " + frame.kind);
            this.frame = frame;
        }
    }

    private static final ThreadLocal<PlayerDecisionFrame> CURRENT = new ThreadLocal<>();
    private final PlayerDecisionFrame previous;
    private final Thread owner = Thread.currentThread();
    private final Kind kind;
    private final Player player;
    private final SpellAbility ability;
    private final forge.game.card.Card combatant;
    private final forge.game.combat.Combat combat;
    private final boolean initiallyOnStack;
    private boolean closed;

    private PlayerDecisionFrame(final Kind kind, final Player player, final SpellAbility ability) {
        this(kind, player, ability, null, null);
    }

    private PlayerDecisionFrame(final Kind kind, final Player player, final SpellAbility ability,
                                final forge.game.card.Card combatant, final forge.game.combat.Combat combat) {
        this.kind = java.util.Objects.requireNonNull(kind);
        this.player = java.util.Objects.requireNonNull(player);
        this.ability = ability;
        this.combatant = combatant;
        this.combat = combat;
        initiallyOnStack = ability != null && player.getGame() != null
                && player.getGame().getStack().getInstanceMatchingSpellAbilityID(ability.getRootAbility()) != null;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static PlayerDecisionFrame enter(final Kind kind, final Player player, final SpellAbility ability) {
        return new PlayerDecisionFrame(kind, player, ability);
    }

    /** A departed defender/source ends only this creature's damage assignment, not everyone else's. */
    public static PlayerDecisionFrame enterCombat(final forge.game.card.Card card, final forge.game.combat.Combat combat) {
        return new PlayerDecisionFrame(Kind.PLAYER_ACTION, card.getController(), null, card,
                java.util.Objects.requireNonNull(combat));
    }

    public static PlayerDecisionFrame current() { return CURRENT.get(); }
    public Kind kind() { return kind; }
    public Player player() { return player; }
    public SpellAbility ability() { return ability; }

    public void abort() {
        requireOwner();
        if (closed) throw new IllegalStateException("Decision frame already closed");
        if (!isAbandoned()) throw new IllegalStateException("Cannot abandon a surviving player's decision/object");
        throw new Aborted(this);
    }

    public boolean isAbandoned() {
        if (combatant != null) {
            if (!player.isInGame() || !combatant.isInPlay()) return true;
            if (combat.isAttacking(combatant)) {
                final Player defender = combat.getDefenderPlayerByAttacker(combatant);
                return defender == null || !defender.isInGame();
            }
            return !combat.isBlocking(combatant) || !combat.getAttackingPlayer().isInGame();
        }
        if (kind == Kind.RESOLVE && initiallyOnStack) {
            return player.getGame().getStack().getInstanceMatchingSpellAbilityID(ability.getRootAbility()) == null;
        }
        return !player.isInGame();
    }

    /** A nested mana/payment frame must never accidentally swallow its parent's abort. */
    public boolean owns(final Aborted aborted) { return aborted.frame == this; }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Decision frame belongs to another rules thread");
    }

    @Override public void close() {
        requireOwner();
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Decision frames must close in stack order");
        closed = true;
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
