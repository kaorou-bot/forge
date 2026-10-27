/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.gamemodes.match.input;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.match.Selection;
import forge.localinstance.properties.ForgePreferences;
import forge.gui.GuiRequestScope;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;
import forge.util.Localizer;

import java.util.List;

/**
 * <p>
 * Abstract Input class.
 * </p>
 *
 * @author Forge
 * @version $Id: InputBase.java 24769 2014-02-09 13:56:04Z Hellfish $
 */
public abstract class InputBase implements java.io.Serializable, Input {
    private static final long serialVersionUID = -2531867688249685076L;

    private final PlayerControllerHuman controller;
    public InputBase(final PlayerControllerHuman controller0) {
        controller = controller0;
    }
    public final PlayerControllerHuman getController() {
        return controller;
    }
    @Override
    public PlayerView getOwner() {
        final Player owner = getController().getPlayer();
        return owner == null ? null : owner.getView();
    }

    private volatile boolean finished = false;
    private transient InputActionGate actionGate;

    private synchronized InputActionGate actionGate() {
        if (actionGate == null) actionGate = new InputActionGate();
        return actionGate;
    }

    protected GuiRequestScope requestScope() { return null; }

    protected final void closeActions() { actionGate().close(); }

    protected final void awaitActions() throws InterruptedException { actionGate().awaitIdle(); }

    public final void runInputAction(final Runnable action) {
        callInputAction(() -> { action.run(); return null; }, null);
    }

    /** Reserve before enqueueing: the rules waiter must also drain not-yet-started actions. */
    protected final void enqueueInputAction(final java.util.function.Consumer<Runnable> enqueue, final Runnable action) {
        final InputActionGate gate = actionGate();
        if (!gate.enter()) return;
        final GuiRequestScope owner = requestScope();
        final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        if (owner != null) owner.activity().processingStarted();
        final Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                try { if (owner != null) owner.activity().processingFinished(); }
                finally { gate.leave(); }
            }
        };
        try {
            enqueue.accept(() -> {
                try {
                    if (!isFinished()) GuiRequestScope.runAction(owner, action);
                } catch (final GuiRequestScope.Cancelled cancelled) {
                    if (owner == null || !owner.isCancelled()) throw cancelled;
                } finally { release.run(); }
            });
        } catch (final RuntimeException | Error failure) {
            release.run();
            throw failure;
        }
    }

    protected final <T> T callInputAction(final java.util.function.Supplier<T> action, final T ignored) {
        final InputActionGate gate = actionGate();
        if (!gate.enter()) return ignored;
        final GuiRequestScope owner = requestScope();
        boolean processing = false;
        try {
            if (isFinished()) return ignored;
            if (owner != null) { owner.activity().processingStarted(); processing = true; }
            final java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>(ignored);
            GuiRequestScope.runAction(requestScope(), () -> result.set(action.get()));
            return result.get();
        } catch (final GuiRequestScope.Cancelled cancelled) {
            // This is the UI/action stack, not a fabricated answer to the rules waiter.
            if (requestScope() == null || !requestScope().isCancelled()) throw cancelled;
            return ignored;
        } finally {
            try { if (processing) owner.activity().processingFinished(); }
            finally { gate.leave(); }
        }
    }
    protected final boolean isFinished() { return finished; }
    protected final void setFinished() {
        closeActions();
        finished = true;

        if (allowAwaitNextInput()) {
            controller.awaitNextInput();
        }
    }

    protected boolean allowAwaitNextInput() {
        return false;
    }

    // showMessage() is always the first method called
    @Override
    public final void showMessageInitial() {
        finished = false;
        runInputAction(() -> {
            controller.cancelAwaitNextInput();
            showMessage();
            // showMessage can finish the input, which has already reset the selection.
            if (!isFinished()) {
                showSelection();
            }
            if (!isFinished() && requestScope() != null) requestScope().inputReady();
        });
    }

    protected abstract void showMessage();

    /** What this prompt marks. */
    protected Selection getSelection() {
        return Selection.NONE;
    }

    /** The cards this prompt highlights as worth acting on. */
    protected List<CardView> getActionable() {
        return List.of();
    }

    /** Sends this prompt's marks, with the targets kept marked while a spell's cost is paid. */
    protected final void showSelection() {
        controller.getGui().setSelection(getSelection().withChosen(controller.getKeptMarks()).withActionable(getActionable()));
    }

    @Override
    public final void selectPlayer(final Player player, final ITriggerEvent triggerEvent) {
        runInputAction(() -> onPlayerSelected(player, triggerEvent));
    }

    @Override
    public final boolean selectAbility(final SpellAbility ab) {
        return callInputAction(() -> onAbilitySelected(ab), false);
    }

    protected boolean onAbilitySelected(final SpellAbility ab) {
        return false;
    }

    @Override
    public final void selectButtonCancel() {
        runInputAction(this::onCancel);
    }

    @Override
    public final void selectButtonOK() {
        runInputAction(this::onOk);
    }

    @Override
    public final boolean selectCard(final Card c, final List<Card> otherCardsToSelect, final ITriggerEvent triggerEvent) {
        return callInputAction(() -> onCardSelected(c, otherCardsToSelect, triggerEvent), false);
    }

    protected boolean onCardSelected(final Card c, final List<Card> otherCardsToSelect, final ITriggerEvent triggerEvent) {
        return false;
    }
    protected void onPlayerSelected(final Player player, final ITriggerEvent triggerEvent) {}
    protected void onCancel() {}
    protected void onOk() {}

    protected final void showMessage(final String message) {
        showMessage(message, (CardView) null);
    }
    protected final void showMessage(final String message, final SpellAbilityView sav) {
        showMessage(message, sav.getHostCard());
    }
    protected final void showMessage(final String message, final CardView card) {
        controller.getGui().showPromptMessage(getOwner(), message, card);
    }

    protected String getTurnPhasePriorityMessage(final Game game) {
        final PhaseHandler ph = game.getPhaseHandler();
        final StringBuilder sb = new StringBuilder();
        Localizer localizer = Localizer.getInstance();
        sb.append(localizer.getMessage("lblPriority")).append(": ").append(ph.getPriorityPlayer()).append("\n");
        sb.append(localizer.getMessage("lblTurn")).append(": ").append(ph.getTurn()).append(" (").append(ph.getPlayerTurn()).append(")");

        if (!game.isNeitherDayNorNight()) {
            sb.append("  [");

            String dayLabel = game.isDay() ? "Day" : "Night";

            sb.append(Localizer.getInstance().getMessage("lbl" + dayLabel));
            sb.append("]");
        }

        sb.append("\n");
        sb.append(localizer.getMessage("lblPhase")).append(": ").append(ph.getPhase().nameForUi).append("\n");
        sb.append(localizer.getMessage("lblStack")).append(": ");
        if (!game.getStack().isEmpty()) {
            sb.append(game.getStack().size()).append(" ").append(localizer.getMessage("lbltoResolve"));
        } else {
            sb.append(localizer.getMessage("lblEmpty"));
        }
        if (FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_SHOW_STORM_COUNT_IN_PROMPT)) {
            int stormCount = game.getView().getStormCount();
            if (stormCount > 0) {
                sb.append("\n").append(localizer.getMessage("lblStormCount")).append(": ").append(stormCount);
            }
        }

        if (controller.macros() != null) {
            boolean isRecording = controller.macros().isRecording();
            String pbText = controller.macros().playbackText();
            if (pbText != null) {
                sb.append("\n");
                if (isRecording) {
                    sb.append("Macro Recording -- ");
                } else {
                    sb.append("Macro Playback -- ");
                }

                sb.append(pbText);
            } else if (isRecording) {
                sb.append("\n").append("Macro Recording -- ");
            }
        }

        return sb.toString();
    }
}
