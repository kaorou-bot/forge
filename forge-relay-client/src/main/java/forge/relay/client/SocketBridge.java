package forge.relay.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Copies bytes in both directions until either connection fails or both reach EOF. */
final class SocketBridge implements AutoCloseable {
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final Socket first;
    private final Socket second;
    private final AtomicInteger openDirections = new AtomicInteger(2);
    private final AtomicBoolean closed = new AtomicBoolean();

    SocketBridge(Socket first, Socket second, Executor executor) {
        this.first = first;
        this.second = second;
        executor.execute(() -> copy(first, second));
        executor.execute(() -> copy(second, first));
    }

    private void copy(Socket source, Socket destination) {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try {
            InputStream in = source.getInputStream();
            OutputStream out = destination.getOutputStream();
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
                out.flush();
            }
            try {
                destination.shutdownOutput();
            } catch (IOException ignored) {
                // The peer may already have closed the complete connection.
            }
            if (openDirections.decrementAndGet() == 0) {
                close();
            }
        } catch (IOException e) {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(first);
        closeQuietly(second);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort tunnel cleanup.
        }
    }
}
