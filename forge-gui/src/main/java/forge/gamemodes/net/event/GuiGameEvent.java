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
    private final byte[] encodedObjects;

    public GuiGameEvent(final ProtocolMethod method, final Object ... objects) {
        // Concurrent broadcasts/GUI requests must not share a reply or cancellation identity.
        this.id = allocateId();
        this.method = method;
        this.objects = objects == null ? new Object[0] : objects;
        this.encodedObjects = null;
    }
    public static int allocateId() {
        final int id = NEXT_ID.getAndUpdate(value -> value == Integer.MAX_VALUE ? value : value + 1);
        if (id == Integer.MAX_VALUE) throw new IllegalStateException("GUI request IDs exhausted");
        return id;
    }
    private GuiGameEvent(final int id, final ProtocolMethod method, final byte[] encodedObjects) {
        this.id = id;
        this.method = method;
        this.objects = new Object[0];
        this.encodedObjects = encodedObjects;
    }

    public GuiGameEvent withEncodedObjects(final byte[] encodedObjects) {
        return new GuiGameEvent(id, method, encodedObjects);
    }

    @Override
    public String toString() {
        return encodedObjects == null
                ? String.format("GuiGameEvent %d: %s (%d args)", id, method, objects.length)
                : String.format("GuiGameEvent %d: %s (%d encoded bytes)", id, method, encodedObjects.length);
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

    /** Null unless the objects travel encoded, in which case {@link #getObjects()} is empty. */
    public byte[] getEncodedObjects() {
        return encodedObjects;
    }

    public boolean replaceTrackables() {
        return replaceTrackables;
    }
}
