package forge.gamemodes.net;

import forge.gui.FThreads;
import forge.relay.client.ConnectionAttempt;
import forge.util.Localizer;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Shared lifecycle for cancellable desktop/mobile lobby operations. */
public final class NetworkOperation {
    private final ConnectionAttempt attempt;
    private volatile boolean finished;

    public NetworkOperation(final Consumer<String> status) {
        attempt = new ConnectionAttempt(stage -> FThreads.invokeInEdtLater(() -> {
            if (!finished) status.accept(stageText(stage));
        }));
    }

    public <T> void start(final Callable<T> task, final Consumer<T> success,
                          final Consumer<Exception> failure, final Runnable cancelled,
                          final Runnable dismiss) {
        FThreads.invokeInBackgroundThread(() -> {
            T value = null;
            Exception error = null;
            try {
                value = attempt.run(task);
            } catch (Exception e) {
                error = e;
            }
            final T result = value;
            final Exception problem = error;
            finished = true;
            FThreads.invokeInEdtLater(() -> {
                dismiss.run();
                if (attempt.isCancelled()) cancelled.run();
                else if (problem != null) failure.accept(problem);
                else success.accept(result);
            });
        });
    }

    public void cancel() {
        FThreads.invokeInBackgroundThread(attempt::cancel);
    }

    public static String stageText(final ConnectionAttempt.Stage stage) {
        final String fallback = switch (stage) {
            case CONNECTING -> "Connecting to server…";
            case SECURING -> "Establishing secure connection…";
            case LISTING -> "Loading rooms…";
            case CREATING -> "Creating room…";
            case JOINING -> "Waiting for the host connection…";
            case CONNECTING_GAME -> "Connecting to the game…";
            case CANCELLING -> "Cancelling connection…";
        };
        return Localizer.getInstance().getMessageorUseDefault("lblNetworkStage" + stage.name(), fallback);
    }
}
