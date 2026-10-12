package forge.screens.home.online;

import forge.gamemodes.net.NetworkOperation;
import forge.gui.FThreads;
import forge.gui.SOverlayUtils;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FOverlay;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

import javax.swing.JPanel;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Cancelling the overlay cancels its sockets as well as closing the visual overlay. */
final class NetworkOperationDialog {
    private static boolean busy;

    private NetworkOperationDialog() { }

    static <T> void run(final String title, final Callable<T> task, final Consumer<T> success,
                        final Consumer<Exception> failure, final Runnable cancelled) {
        FThreads.invokeInEdtNowOrLater(() -> {
            if (busy) return;
            busy = true;
            final JPanel panel = FOverlay.SINGLETON_INSTANCE.getPanel();
            panel.removeAll();
            panel.setLayout(new MigLayout("wrap, align center center", "[center]"));
            final FLabel status = new FLabel.Builder().text(title).fontSize(20).build();
            final NetworkOperation operation = new NetworkOperation(status::setText);
            final FButton cancel = new FButton(Localizer.getInstance().getMessage("lblCancel"));
            cancel.addActionListener(e -> {
                cancel.setEnabled(false);
                operation.cancel();
                status.setText(NetworkOperation.stageText(
                        forge.relay.client.ConnectionAttempt.Stage.CANCELLING));
            });
            panel.add(status, "gapbottom 20");
            panel.add(cancel, "w 160!, h 40!");
            SOverlayUtils.showOverlay();
            operation.start(task, success, failure, cancelled, () -> {
                busy = false;
                SOverlayUtils.hideOverlay();
            });
        });
    }
}
