package forge.gamemodes.net;

import forge.gui.util.SOptionPane;
import forge.util.Localizer;

/** Room-local policy, deliberately outside serialized GameLobbyData. */
public final class OperationClockSettings {
    private OperationClockSettings() { }

    /** Null cancels creation; zero means unlimited. */
    public static Long prompt() {
        final Localizer loc = Localizer.getInstance();
        while (true) {
            final String input = SOptionPane.showInputDialog(loc.getMessage("lblOperationClockPrompt"),
                    loc.getMessage("lblOperationClock"), null, "0", null, false);
            if (input == null) return null;
            try {
                final int minutes = Integer.parseInt(input.trim());
                if (minutes >= 0 && minutes <= 1440) return minutes * 60_000L;
            } catch (final NumberFormatException ignored) { }
            SOptionPane.showMessageDialog(loc.getMessage("lblOperationClockInvalid"));
        }
    }

    public static void validate(final long millis) {
        if (millis < 0 || millis > 86_400_000L) throw new IllegalArgumentException("Invalid operation allowance");
    }

    public static String describe(final long millis) {
        final Localizer loc = Localizer.getInstance();
        return millis == 0 ? loc.getMessage("lblOperationClockUnlimited")
                : loc.getMessage("lblOperationClockPolicy", Long.toString(millis / 60_000));
    }
}
