package forge.gamemodes.quest.bazaar;

import forge.util.Localizer;

/**
 * Display-only translations. XML names, item keys and pet save keys remain unchanged.
 * Unknown/custom bazaar entries keep their original text.
 */
final class QuestBazaarLocalization {
    private QuestBazaarLocalization() {
    }

    static String text(final String kind, final String id, final String field, final String fallback) {
        return Localizer.getInstance().getMessageorUseDefault(
                "questBazaar." + kind + "." + id.replace(" ", "") + "." + field, fallback);
    }
}
