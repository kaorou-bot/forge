package forge.screens.match.winlose;

import java.util.Collection;

import forge.Forge;
import forge.assets.FSkin;
import forge.game.GameView;
import forge.game.player.PlayerView;
import forge.gamemodes.match.NextGameDecision;
import forge.interfaces.IGameController;
import forge.screens.match.MatchController;

/** 
 * Default controller for a ViewWinLose object. This class can
 * be extended for various game modes to populate the custom
 * panel in the win/lose screen.
 *
 */
public class ControlWinLose {
    private final ViewWinLose view;
    protected final GameView lastGame;
    private int humancount;

    /** @param v &emsp; ViewWinLose
     * @param game */
    public ControlWinLose(final ViewWinLose v, GameView game) {
        view = v;
        lastGame = game;
        humancount = 0;
        for(PlayerView p: game.getPlayers()){
            if (!p.isAI())
                humancount++;
        }
        addListeners();
    }

    /** */
    public void addListeners() {
        view.getBtnContinue().setCommand(e -> actionOnContinue());

        view.getBtnRestart().setCommand(e -> actionOnRestart());

        view.getBtnQuit().setCommand(e -> {
            actionOnQuit();
            view.getBtnQuit().setEnabled(false);
        });
        if(humancount == 0)
            view.getBtnRestart().setEnabled(false);
    }

    /** Action performed when "continue" button is pressed in default win/lose UI. */
    public void actionOnContinue() {
        if (sendNetworkDecision(NextGameDecision.CONTINUE)) {
            return;
        }
        view.hide();
        saveOptions();

        try { MatchController.getHostedMatch().continueMatch();
        } catch (NullPointerException e) {}
    }

    /** Action performed when "restart" button is pressed in default win/lose UI. */
    public void actionOnRestart() {
        if (sendNetworkDecision(NextGameDecision.NEW)) {
            return;
        }
        view.hide();
        saveOptions();
        try { MatchController.getHostedMatch().restartMatch();
        } catch (NullPointerException e) {}
    }

    /** Action performed when "quit" button is pressed in default win/lose UI. */
    public void actionOnQuit() {
        if (sendNetworkDecision(NextGameDecision.QUIT)) {
            Forge.setCursor(FSkin.getCursor().get(0), "0");
            return;
        }
        boolean openHomeScreen = false;
        // Reset other stuff
        saveOptions();
        try {
            if(MatchController.getHostedMatch().subGameCount > 0) {
                openHomeScreen = true;
                MatchController.getHostedMatch().subGameCount--;
            }
            MatchController.getHostedMatch().endCurrentGame();
        } catch (NullPointerException e) {}
        view.hide();
        if (openHomeScreen || humancount == 0)
            Forge.openHomeScreen(Forge.lastButtonIndex, Forge.getCurrentScreen());
        //reset cursor
        Forge.setCursor(FSkin.getCursor().get(0), "0");
    }

    /**
     * Network matches do not have a local {@link forge.gamemodes.match.HostedMatch}
     * on a joining client. Send the post-game choice through its network game
     * controller so the host can finish the match and return every client to the
     * lobby. The old mobile path swallowed the resulting null dereference, which
     * made the win/lose buttons appear to do nothing after an online game.
     */
    private boolean sendNetworkDecision(final NextGameDecision decision) {
        if (!MatchController.instance.isNetGame()) {
            return false;
        }

        final Collection<IGameController> controllers =
                MatchController.instance.getOriginalGameControllers();
        if (controllers.isEmpty()) {
            final IGameController spectator = MatchController.instance.getGameController();
            if (spectator == null) {
                return false;
            }
            view.hide();
            saveOptions();
            spectator.nextGameDecision(decision);
            return true;
        }

        view.hide();
        saveOptions();
        for (final IGameController controller : controllers) {
            controller.nextGameDecision(decision);
        }
        return true;
    }

    /**
     * Either continues or restarts a current game. May be overridden for use
     * with other game modes.
     */
    public void saveOptions() {
        MatchController.writeMatchPreferences();
    }

    /**
     * <p>
     * populateCustomPanel.
     * </p>
     * May be overridden as required by controllers for various game modes
     * to show custom information in center panel. Default configuration is empty.
     * 
     * @return boolean, panel has contents or not.
     */
    public void showRewards() {
    }

    /** @return ViewWinLose object this controller is in charge of */
    public ViewWinLose getView() {
        return view;
    }
}
