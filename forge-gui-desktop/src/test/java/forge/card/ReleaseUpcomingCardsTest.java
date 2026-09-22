package forge.card;

import forge.CardStorageReader;
import forge.StaticData;
import forge.gamesimulationtests.util.CardDatabaseHelper;
import forge.item.PaperCard;
import forge.item.generation.BoosterGenerator;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.BuildInfo;
import forge.util.MyRandom;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.testng.Assert.*;

/** Release builds must load the same bundled new cards as development/Android builds. */
public class ReleaseUpcomingCardsTest extends CardMockTestCase {
    private StaticData data;

    @Override
    protected void initializeStaticData() {
        // Tests normally run without an Implementation-Version manifest and look like GIT builds.
        try (MockedStatic<BuildInfo> build = Mockito.mockStatic(BuildInfo.class)) {
            build.when(BuildInfo::isDevelopmentVersion).thenReturn(false);
            data = CardDatabaseHelper.createStaticData("ReleaseUpcomingCardsTest", false);
        }
        fModelMock.when(FModel::getMagicDb).thenReturn(data);
    }

    @Test
    public void releaseLoadsEveryFraCommon() {
        int checked = 0;
        for (CardEdition.EditionEntry entry : data.getEditions().get("FRA").getCards()) {
            if (entry.rarity() != CardRarity.Common) {
                continue;
            }
            PaperCard card = data.getCommonCards().getCard(entry.name(), "FRA");
            assertNotNull(card, "Release omitted FRA common: " + entry.name());
            assertEquals(card.getName(), entry.name());
            assertEquals(card.getEdition(), "FRA");
            checked++;
        }
        assertTrue(checked > 60, "The regression must exercise the full FRA common pool");
    }

    @Test
    public void releaseLazyReaderAlsoFindsUpcomingCards() {
        try (MockedStatic<BuildInfo> build = Mockito.mockStatic(BuildInfo.class)) {
            build.when(BuildInfo::isDevelopmentVersion).thenReturn(false);
            CardStorageReader reader = new CardStorageReader(ForgeConstants.CARD_DATA_DIR, null, true);
            CardRules card = reader.attemptToLoadCard("Jace, Reality Sculptor");
            assertNotNull(card, "Lazy release lookup omitted upcoming card");
            assertEquals(card.getName(), "Jace, Reality Sculptor");
        }
    }

    @Test
    public void releaseGeneratesSixCompleteFraBoosters() {
        Random previousRandom = MyRandom.getRandom();
        try {
            MyRandom.setRandom(new Random(20260922L));
            verifySixFraBoosters();
        } finally {
            MyRandom.setRandom(previousRandom);
        }
    }

    private void verifySixFraBoosters() {
        Set<String> names = new HashSet<>();
        int newCommons = 0;
        for (int pack = 0; pack < 6; pack++) {
            List<PaperCard> cards = BoosterGenerator.getBoosterPack(data.getBoosters().get("FRA"));
            assertEquals(cards.size(), 14, "Incomplete FRA booster " + pack);
            for (PaperCard card : cards) {
                assertNotNull(card);
                names.add(card.getName());
                if (card.getRarity() == CardRarity.Common && "FRA".equals(card.getEdition())
                        && !Set.of("Unsummon", "Last Gasp", "Blazing Crescendo").contains(card.getName())) {
                    newCommons++;
                }
            }
        }
        assertTrue(names.size() > 20, "Sealed pool collapsed to a few reprints: " + names);
        assertTrue(newCommons > 0, "Sealed pool contains no new FRA commons");
    }
}
