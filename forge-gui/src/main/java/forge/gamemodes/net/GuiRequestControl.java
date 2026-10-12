package forge.gamemodes.net;

import forge.gamemodes.net.event.ReplyEvent;

/**
 * Optional client UI cancellation, negotiated in the existing login version string.
 *
 * The thin serialization descriptors require identical instance fields, so do not add a
 * capability field to LoginEvent. Old hosts warn about version differences but can still log in.
 * Never send unsolicited control replies: old hosts throw on unknown reply indices.
 * Only clients advertising the exact suffix may receive cancellation envelopes.
 * This is UI cleanup only, not an engine answer, a time budget, or a timeout verdict.
 */
public final class GuiRequestControl {
    public static final int INDEX = Integer.MIN_VALUE;
    private static final String SUFFIX = ";forge-gui-cancel=1";
    private static final String CLOCK_SUFFIX = ";forge-gui-cancel=2";
    public static final String HELLO = "forge-clock-v2";

    private GuiRequestControl() { }

    public static String loginVersion(final String version) {
        if (version == null || version.isEmpty() || version.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Invalid base version");
        }
        return version + CLOCK_SUFFIX;
    }

    public static boolean supportsCancellation(final String version) {
        return hasSuffix(version, SUFFIX) || supportsClock(version);
    }

    private static boolean hasSuffix(final String version, final String suffix) {
        return version != null && version.endsWith(suffix) && version.indexOf(';') > 0
                && version.indexOf(';') == version.length() - suffix.length();
    }

    public static boolean supportsClock(final String version) { return hasSuffix(version, CLOCK_SUFFIX); }

    public static String baseVersion(final String version) {
        return supportsCancellation(version) ? version.substring(0, version.indexOf(';')) : version;
    }

    public static ReplyEvent control(final String value) { return new ReplyEvent(INDEX, value); }
    public static ReplyEvent ready(final int id) { return control("ready:" + id); }
    public static ReplyEvent barrier(final int id) { return control("barrier:" + id); }

    public static Integer requestId(final Object value, final String prefix) {
        if (!(value instanceof String text) || !text.startsWith(prefix) || text.length() > prefix.length() + 10) return null;
        try {
            final int id = Integer.parseInt(text.substring(prefix.length()));
            return id >= 0 && text.equals(prefix + id) ? id : null;
        } catch (final NumberFormatException ignored) { return null; }
    }

    public static ReplyEvent cancel(final int requestId) {
        if (requestId < 0) throw new IllegalArgumentException("Invalid GUI request ID");
        return new ReplyEvent(INDEX, requestId);
    }

    public static Integer cancelledRequest(final Object value) {
        return value instanceof Integer id && id >= 0 ? id : null;
    }
}
