package forge.screens.online;

import forge.gamemodes.net.NetworkOperation;
import forge.gui.FThreads;
import forge.toolbox.FOptionPane;
import forge.util.Localizer;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** One cancellable lobby operation at a time; callbacks always run on the render thread. */
final class NetworkOperationDialog {
    private static boolean busy;

    private NetworkOperationDialog() { }

    static <T> void run(final String title, final Callable<T> task, final Consumer<T> success,
                        final Consumer<Exception> failure, final Runnable cancelled) {
        FThreads.invokeInEdtNowOrLater(() -> {
            if (busy) return;
            busy = true;
            final FOptionPane[] pane = new FOptionPane[1];
            final NetworkOperation operation = new NetworkOperation(text -> pane[0].setMessage(text));
            pane[0] = new FOptionPane(title, null, title, FOptionPane.INFORMATION_ICON, null,
                    List.of(Localizer.getInstance().getMessage("lblCancel")), 0,
                    result -> operation.cancel());
            pane[0].show();
            operation.start(task, success, failure, cancelled, () -> {
                busy = false;
                pane[0].hide();
            });
        });
    }
}
