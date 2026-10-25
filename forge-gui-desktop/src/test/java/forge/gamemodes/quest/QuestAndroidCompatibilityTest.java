package forge.gamemodes.quest;

import forge.deck.CardPool;
import forge.gamemodes.quest.data.GameFormatQuest;
import forge.gamemodes.quest.data.QuestAssets;
import forge.gamemodes.quest.data.QuestPreferences;
import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.item.generation.UnOpenedProduct;
import forge.model.FModel;
import forge.util.ItemPool;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/** JVM behavioral regression; Android API coverage comes from the separate DEX audit. */
public class QuestAndroidCompatibilityTest {
    @DataProvider(name = "productCases")
    public Object[][] productCases() {
        return new Object[][] {{true, false}, {true, true}, {false, false}, {false, true}};
    }

    @Test(dataProvider = "productCases")
    public void productPathsPreserveFilteringAndEmptyLists(final boolean shop, final boolean empty) throws Exception {
        try (var model = mockStatic(FModel.class)) {
            final QuestController quest = mock(QuestController.class);
            final QuestAssets assets = mock(QuestAssets.class);
            final GameFormatQuest format = mock(GameFormatQuest.class);
            final PaperCard allowed = mock(PaperCard.class);
            final PaperCard excluded = mock(PaperCard.class);
            final PaperCard generated = mock(PaperCard.class);
            final ItemPool<InventoryItem> inventory = new ItemPool<>(InventoryItem.class);
            model.when(FModel::getQuestPreferences).thenReturn(mock(QuestPreferences.class));
            when(quest.getAssets()).thenReturn(assets);
            when(assets.getShopList()).thenReturn(inventory);
            when(quest.getFormat()).thenReturn(format);
            when(format.getFilterPrinted()).thenReturn(card -> card == allowed);
            when(quest.getAllAllowedCards()).thenAnswer(call ->
                    (empty ? Collections.<PaperCard>emptyList() : Arrays.asList(excluded, allowed, allowed)).stream());
            final List<List<PaperCard>> suppliedPools = new ArrayList<>();
            final List<PaperCard> generatedCards = empty ? Collections.emptyList() : Collections.singletonList(generated);
            // Isolate random print-sheet generation while executing the real Quest caller and inventory mutations.
            try (var products = mockConstruction(UnOpenedProduct.class, (product, context) -> {
                final List<PaperCard> supplied = new ArrayList<>();
                for (Object card : (Iterable<?>) context.arguments().get(1)) {
                    supplied.add((PaperCard) card);
                }
                suppliedPools.add(supplied);
                when(product.get()).thenReturn(generatedCards);
            })) {
                final QuestUtilCards cards = new QuestUtilCards(quest);
                if (shop) {
                    final Method generate = QuestUtilCards.class.getDeclaredMethod("generateSinglesInShop", int.class);
                    generate.setAccessible(true);
                    generate.invoke(cards, 2);
                    assertEquals(inventory.count(generated), empty ? 0 : 2);
                    assertEquals(products.constructed().size(), 2);
                } else {
                    assertEquals(cards.generateQuestBooster(card -> card == allowed), generatedCards);
                    assertEquals(products.constructed().size(), 1);
                }
                for (List<PaperCard> supplied : suppliedPools) {
                    assertEquals(supplied, empty ? Collections.emptyList() : Arrays.asList(allowed, allowed));
                }
                assertEquals(inventory.count(excluded), 0);
            }
        }
    }

    @DataProvider(name = "rewardCases")
    public Object[][] rewardCases() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "rewardCases")
    public void rewardsRespectFormatAndUpdateOwnedAndNewPools(final boolean empty) {
        try (var model = mockStatic(FModel.class)) {
            final QuestController quest = mock(QuestController.class);
            final QuestAssets assets = mock(QuestAssets.class);
            final GameFormatQuest format = mock(GameFormatQuest.class);
            final PaperCard allowed = mock(PaperCard.class);
            final PaperCard excluded = mock(PaperCard.class);
            final CardPool owned = new CardPool();
            final ItemPool<InventoryItem> fresh = new ItemPool<>(InventoryItem.class);
            model.when(FModel::getQuestPreferences).thenReturn(mock(QuestPreferences.class));
            when(quest.getAssets()).thenReturn(assets);
            when(quest.getFormat()).thenReturn(format);
            when(format.getFilterPrinted()).thenReturn(card -> card == allowed);
            when(quest.getAllAllowedCards()).thenAnswer(call ->
                    (empty ? Collections.<PaperCard>emptyList() : Arrays.asList(excluded, allowed)).stream());
            when(assets.getCardPool()).thenReturn(owned);
            when(assets.getNewCardList()).thenReturn(fresh);
            final List<PaperCard> reward = new QuestUtilCards(quest).addRandomCards(1, card -> true);
            assertEquals(reward, empty ? Collections.emptyList() : Collections.singletonList(allowed));
            assertEquals(owned.count(allowed), empty ? 0 : 1);
            assertEquals(fresh.count(allowed), empty ? 0 : 1);
            assertEquals(owned.count(excluded), 0);
            assertEquals(fresh.count(excluded), 0);
        }
    }
}
