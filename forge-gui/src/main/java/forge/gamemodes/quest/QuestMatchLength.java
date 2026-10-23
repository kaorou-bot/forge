package forge.gamemodes.quest;

import forge.util.Localizer;

/** Display labels must not be parsed as numbers: Chinese labels need not end in a digit. */
public final class QuestMatchLength {
    private QuestMatchLength() {
    }

    public static String labelForValue(final int value) {
        final Localizer localizer = Localizer.getInstance();
        if (value == 1 || value == 3 || value == 5) {
            return localizer.getMessage("lblMatchBestOf" + value);
        }
        return localizer.getMessage("lblMatchBestof") + " " + value;
    }

    public static Integer valueForLabel(final String label) {
        for (int value : new int[] {1, 3, 5}) {
            if (labelForValue(value).equals(label)) {
                return value;
            }
        }
        // Null/unknown selections can occur while a combo box is being rebuilt.
        return null;
    }
}
