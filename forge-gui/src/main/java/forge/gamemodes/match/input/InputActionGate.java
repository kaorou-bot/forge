package forge.gamemodes.match.input;

/**
 * Stops accepting actions immediately, without waiting on the timer/UI thread.
 * The original waiter drains already admitted actions before inspecting their result or
 * applying rules-side cancellation. No monitor is held while an action opens a dialog.
 */
final class InputActionGate {
    private boolean closed;
    private int active;

    synchronized boolean enter() {
        if (closed) return false;
        active++;
        return true;
    }

    synchronized void leave() {
        if (active <= 0) throw new IllegalStateException("Unbalanced input action");
        active--;
        notifyAll();
    }

    synchronized void close() { closed = true; }

    synchronized void awaitIdle() throws InterruptedException {
        while (active != 0) wait();
    }
}
