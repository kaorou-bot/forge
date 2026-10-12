package forge.gamemodes.net.event;

import forge.gamemodes.net.ProtocolMethod;

public final class GuiGameEvent implements IdentifiableNetEvent {
    private static final long serialVersionUID = 6223690008522514574L;
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID =
            new java.util.concurrent.atomic.AtomicInteger();

    private final int id;
    private final ProtocolMethod method;
    private final Object[] objects;
    private boolean replaceTrackables = true;

    public GuiGameEvent(final ProtocolMethod method, final Object ... objects) {
        // Concurrent broadcasts/GUI requests must not share a reply or cancellation identity.
        this.id = allocateId();
        this.method = method;
        this.objects = objects == null ? new Object[0] : objects;
    }
    public static int allocateId() {
        final int id = NEXT_ID.getAndUpdate(value -> value == Integer.MAX_VALUE ? value : value + 1);
        if (id == Integer.MAX_VALUE) throw new IllegalStateException("GUI request IDs exhausted");
        return id;
    }
    public GuiGameEvent(final ProtocolMethod method, boolean replace, final Object ... objects) {
        this(method, objects);
        replaceTrackables = replace;
    }

    @Override
    public String toString() {
        return String.format("GuiGameEvent %d: %s (%d args)", id, method, objects.length);
    }

    @Override
    public int getId() {
        return id;
    }

    public ProtocolMethod getMethod() {
        return method;
    }

    public Object[] getObjects() {
        return objects;
    }

    public boolean replaceTrackables() {
        return replaceTrackables;
    }
}
