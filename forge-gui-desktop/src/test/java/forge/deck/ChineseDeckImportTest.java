package forge.deck;

import forge.card.CardMockTestCase;
import forge.localinstance.properties.ForgeConstants;
import forge.util.CardTranslation;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.testng.Assert.*;

public class ChineseDeckImportTest extends CardMockTestCase {
    private void assertCard(String line, String name, int quantity, DeckSection section) {
        DeckRecognizer.Token token = new DeckRecognizer().recognizeLine(line, DeckSection.Main);
        assertNotNull(token, line);
        assertNotNull(token.getCard(), line + ": " + token.getType());
        assertEquals(token.getCard().getName(), name, line);
        assertEquals(token.getQuantity(), quantity, line);
        assertEquals(token.getTokenSection(), section, line);
    }

    @Test
    public void importsChineseAndMixedNamesWithEitherInterfaceLanguage() {
        String previous = CardTranslation.getLanguageSelected();
        try {
            for (String language : new String[]{"zh-CN", "en-US"}) {
                CardTranslation.preloadTranslation(language, ForgeConstants.LANG_DIR);
                assertCard("4 闪电击", "Lightning Bolt", 4, DeckSection.Main);
                assertCard("2 Lightning Bolt", "Lightning Bolt", 2, DeckSection.Main);
                assertCard("SB: 2 闪电击 (M11) 146", "Lightning Bolt", 2, DeckSection.Sideboard);
                assertCard("1 掘密师 // 昆虫变体", "Delver of Secrets", 1, DeckSection.Main);
                assertCard("1 昆虫变体", "Delver of Secrets", 1, DeckSection.Main);
                assertCard("1 热火 / 寒冰", "Fire // Ice", 1, DeckSection.Main);
                assertCard("1 风暴之眼乌金", "Ugin, Eye of the Storms", 1, DeckSection.Main);
                assertCard("1 无畏勇士琳‧西薇", "Lin Sivvi, Defiant Hero", 1, DeckSection.Main);
                assertCard("1 掠夺𫠒人", "Cephalid Looter", 1, DeckSection.Main);
                assertNull(new DeckRecognizer().recognizeLine("1 不存在的中文牌名", DeckSection.Main).getCard());
            }
        } finally {
            CardTranslation.preloadTranslation(previous, ForgeConstants.LANG_DIR);
        }
    }

    @Test
    public void acceptsPackagedChineseNameCharacters() throws Exception {
        Pattern pattern = Pattern.compile(DeckRecognizer.REX_CARD_NAME);
        for (String line : Files.readAllLines(Path.of(ForgeConstants.LANG_DIR, "cardnames-zh-CN.txt"))) {
            String[] fields = line.split("\\|", 4);
            // ASCII parentheses retain the upstream limitation: they delimit set codes.
            if (fields.length < 2 || fields[1].isBlank() || fields[1].contains("(") || fields[1].contains(")")) {
                continue;
            }
            assertTrue(pattern.matcher(fields[1]).matches(), fields[0] + ": " + fields[1]);
        }
    }

    @Test
    public void rejectsAmbiguousTranslationsAndClearsMissingIndex() throws Exception {
        String previous = CardTranslation.getLanguageSelected();
        Path directory = Files.createTempDirectory("forge-import-names-");
        Path translations = directory.resolve("cardnames-zh-CN.txt");
        try {
            Files.writeString(translations,
                    "Lightning Bolt|同名牌|瞬间|规则带有|分隔符\n"
                    + "Shock|同名牌|瞬间|规则\n"
                    + "Island|海岛，测试|地|规则\n", StandardCharsets.UTF_8);
            CardTranslation.preloadTranslation("en-US", directory.toString());
            assertNull(new DeckRecognizer().recognizeLine("1 同名牌", DeckSection.Main).getCard());
            assertCard("1 海岛,测试", "Island", 1, DeckSection.Main);
            Files.delete(translations);
            CardTranslation.preloadTranslation("en-US", directory.toString());
            assertTrue(CardTranslation.getEnglishNamesForChineseName("海岛，测试").isEmpty());
            assertCard("1 Island", "Island", 1, DeckSection.Main);
        } finally {
            Files.deleteIfExists(translations);
            Files.deleteIfExists(directory);
            CardTranslation.preloadTranslation(previous, ForgeConstants.LANG_DIR);
        }
    }
}
