package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.AdventureQuestStage;
import forge.adventure.data.DialogData;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional, plane-specific text overlay, applied before quest token substitution.
 * Exact source matching prevents stale translations from replacing changed upstream text.
 * Only display fields are visited; IDs, actions, conditions and reward data stay untouched.
 * No fields are added to serializable quest/save classes.
 */
public final class AdventureQuestLocalization {
    private AdventureQuestLocalization() {
    }

    public static void apply(final Array<AdventureQuestData> quests, final FileHandle file) {
        if (!file.exists()) {
            return;
        }
        final Map<Integer, Map<String, String>> byQuest = new HashMap<>();
        try {
            final JsonValue root = new JsonReader().parse(file.readString("UTF-8"));
            if (!root.isArray()) {
                throw new IllegalArgumentException("Expected an array of source/translation pairs");
            }
            for (JsonValue entry = root.child; entry != null; entry = entry.next) {
                final String source = entry.getString("source");
                final String translated = entry.getString("translation");
                final int questId = entry.getInt("questId");
                if (questId < 1) {
                    throw new IllegalArgumentException("Expected a positive quest ID");
                }
                final Map<String, String> translations = byQuest.computeIfAbsent(questId, id -> new HashMap<>());
                if (source.isEmpty() || translated.isEmpty() || translations.put(source, translated) != null) {
                    throw new IllegalArgumentException("Empty or duplicate quest translation");
                }
            }
        } catch (RuntimeException ex) {
            // A broken optional translation file must never prevent entering the adventure.
            System.err.println("Unable to load quest translations " + file + ": " + ex.getMessage());
            return;
        }
        for (AdventureQuestData quest : quests) {
            final Map<String, String> translations = byQuest.get(quest.getID());
            if (translations == null) {
                continue;
            }
            quest.name = text(translations, quest.name);
            quest.description = text(translations, quest.description);
            quest.rewardDescription = text(translations, quest.rewardDescription);
            dialog(translations, quest.offerDialog);
            dialog(translations, quest.prologue);
            dialog(translations, quest.epilogue);
            dialog(translations, quest.failureDialog);
            dialog(translations, quest.declinedDialog);
            for (AdventureQuestStage stage : quest.stages) {
                stage.name = text(translations, stage.name);
                stage.description = text(translations, stage.description);
                dialog(translations, stage.prologue);
                dialog(translations, stage.epilogue);
                dialog(translations, stage.failureDialog);
            }
        }
    }

    private static String text(final Map<String, String> translations, final String source) {
        return translations.getOrDefault(source, source);
    }

    private static void dialog(final Map<String, String> translations, final DialogData dialog) {
        if (dialog == null) {
            return;
        }
        // Existing explicit locale keys retain precedence over this optional overlay.
        if (dialog.locname == null || dialog.locname.isEmpty()) {
            dialog.name = text(translations, dialog.name);
        }
        if (dialog.loctext == null || dialog.loctext.isEmpty()) {
            dialog.text = text(translations, dialog.text);
        }
        for (DialogData option : dialog.options) {
            dialog(translations, option);
        }
    }
}
