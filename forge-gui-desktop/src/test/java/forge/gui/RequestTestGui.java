package forge.gui;

import forge.gui.interfaces.IGuiBase;
import java.lang.reflect.Proxy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A queued UI dispatcher, not a graphical/device test. Tests using it must run serially. */
public final class RequestTestGui implements AutoCloseable {
    private final IGuiBase previous = GuiBase.getInterface();
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    public RequestTestGui() {
        GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                new Class<?>[] { IGuiBase.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "invokeInEdtLater": queue.add((Runnable) args[0]); return null;
                        case "invokeInEdtNow":
                        case "invokeInEdtAndWait": ((Runnable) args[0]).run(); return null;
                        case "isLibgdxPort": return true;
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            return null;
                    }
                }));
    }

    public Runnable next() throws InterruptedException {
        final Runnable next = queue.poll(2, TimeUnit.SECONDS);
        if (next == null) throw new AssertionError("UI task was never queued");
        return next;
    }

    @Override public void close() { GuiBase.setInterface(previous); }
}
