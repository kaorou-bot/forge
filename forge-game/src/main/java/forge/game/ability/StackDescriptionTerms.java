package forge.game.ability;

import forge.util.Localizer;

import java.util.Locale;
import java.util.Map;

/**
 * Display-only vocabulary for scripted types and keywords. Never pass card/player names here.
 * Match whole known terms only: unknown restrictions survive verbatim, without guessing.
 */
public final class StackDescriptionTerms {
    private static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry("card", "lblStackTermCard"),
            Map.entry("creature", "lblStackTermCreature"),
            Map.entry("artifact", "lblStackTermArtifact"),
            Map.entry("enchantment", "lblStackTermEnchantment"),
            Map.entry("land", "lblStackTermLand"),
            Map.entry("planeswalker", "lblStackTermPlaneswalker"),
            Map.entry("permanent", "lblStackTermPermanent"),
            Map.entry("nonland permanent", "lblStackTermNonlandPermanent"),
            Map.entry("nonland card", "lblStackTermNonlandCard"),
            Map.entry("noncreature permanent", "lblStackTermNoncreaturePermanent"),
            Map.entry("artifact creature", "lblStackTermArtifactCreature"),
            Map.entry("creature card", "lblStackTermCreatureCard"),
            Map.entry("artifact card", "lblStackTermArtifactCard"),
            Map.entry("basic land", "lblStackTermBasicLand"),
            Map.entry("food", "lblStackTermFood"),
            Map.entry("treasure", "lblStackTermTreasure"),
            Map.entry("clue", "lblStackTermClue"),
            Map.entry("blood", "lblStackTermBlood"),
            Map.entry("flying", "lblStackTermFlying"),
            Map.entry("haste", "lblStackTermHaste"),
            Map.entry("trample", "lblStackTermTrample"),
            Map.entry("vigilance", "lblStackTermVigilance"),
            Map.entry("lifelink", "lblStackTermLifelink"),
            Map.entry("deathtouch", "lblStackTermDeathtouch"),
            Map.entry("reach", "lblStackTermReach"),
            Map.entry("menace", "lblStackTermMenace"),
            Map.entry("hexproof", "lblStackTermHexproof"),
            Map.entry("indestructible", "lblStackTermIndestructible"),
            Map.entry("defender", "lblStackTermDefender"),
            Map.entry("first strike", "lblStackTermFirstStrike"),
            Map.entry("double strike", "lblStackTermDoubleStrike"),
            Map.entry("shroud", "lblStackTermShroud"),
            Map.entry("infect", "lblStackTermInfect"),
            Map.entry("wither", "lblStackTermWither"),
            Map.entry("fear", "lblStackTermFear"),
            Map.entry("white", "lblStackTermWhite"),
            Map.entry("blue", "lblStackTermBlue"),
            Map.entry("black", "lblStackTermBlack"),
            Map.entry("red", "lblStackTermRed"),
            Map.entry("green", "lblStackTermGreen"),
            Map.entry("colorless", "lblStackTermColorless"),
            Map.entry("soldier", "lblStackTermSoldier"),
            Map.entry("goblin", "lblStackTermGoblin"),
            Map.entry("zombie", "lblStackTermZombie"),
            Map.entry("human", "lblStackTermHuman"),
            Map.entry("elf", "lblStackTermElf"),
            Map.entry("spirit", "lblStackTermSpirit"),
            Map.entry("beast", "lblStackTermBeast"),
            Map.entry("saproling", "lblStackTermSaproling"),
            Map.entry("angel", "lblStackTermAngel")
    );

    private StackDescriptionTerms() { }

    public static boolean isKnown(String text) {
        return text != null && KEYS.containsKey(text.toLowerCase(Locale.ROOT));
    }

    public static String translate(String text) {
        if (text == null) {
            return "";
        }
        String key = KEYS.get(text.toLowerCase(Locale.ROOT));
        return key == null ? text : Localizer.getInstance().getMessage(key);
    }
}
