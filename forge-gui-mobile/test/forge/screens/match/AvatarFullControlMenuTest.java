package forge.screens.match;

import com.badlogic.gdx.Gdx;
import forge.Forge;
import forge.MobileTestEnvironment;
import forge.assets.FSkinFont;
import forge.game.Game;
import forge.game.GameView;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.player.PlayerView;
import forge.gui.FThreads;
import forge.menu.FPopupMenu;
import forge.screens.match.views.VPlayerPanel;
import forge.util.ThreadUtil;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class AvatarFullControlMenuTest {
    private com.badlogic.gdx.Graphics previousGraphics;
    private com.badlogic.gdx.Application previousApp;

    @BeforeMethod
    public void initializeGraphics() {
        previousGraphics = Gdx.graphics;
        previousApp = Gdx.app;
        Gdx.app = mock(com.badlogic.gdx.Application.class);
        Gdx.graphics = mock(com.badlogic.gdx.Graphics.class);
        when(Gdx.graphics.getWidth()).thenReturn(1080);
        when(Gdx.graphics.getHeight()).thenReturn(1920);
    }

    @AfterMethod
    public void restoreGraphics() {
        Gdx.graphics = previousGraphics;
        Gdx.app = previousApp;
    }

    @BeforeClass
    public void initialize() throws Exception {
        MobileTestEnvironment.initialize();
    }

    @DataProvider
    public Object[][] initialFlags() {
        return new Object[][] {{true, "replaced-game"}, {false, "replaced-game"},
                {true, "cleared-game"}, {false, "cleared-game"},
                {true, "left-screen"}, {false, "left-screen"},
                {true, "replaced-screen"}, {false, "replaced-screen"},
                {true, "game-over"}, {false, "game-over"}};
    }

    @Test(dataProvider = "initialFlags")
    public void menuConstructionUsesUiQueueAndTogglesUseGameQueue(boolean initiallyChecked, String invalidation) {
        try (var fonts = mockStatic(FSkinFont.class);
             var ui = mockStatic(FThreads.class);
             var gameThreads = mockStatic(ThreadUtil.class);
             var match = mockStatic(MatchController.class);
             var panels = mockStatic(MatchScreen.class);
             var forge = mockStatic(Forge.class)) {
            fonts.when(() -> FSkinFont.get(anyInt())).thenReturn(mock(FSkinFont.class));
            final ArrayDeque<Runnable> uiQueue = new ArrayDeque<>();
            final ArrayDeque<Runnable> gameQueue = new ArrayDeque<>();
            ui.when(() -> FThreads.invokeInEdtLater(any(Runnable.class))).thenAnswer(call -> {
                uiQueue.add(call.getArgument(0));
                return null;
            });
            gameThreads.when(() -> ThreadUtil.invokeInGameThread(any(Runnable.class))).thenAnswer(call -> {
                gameQueue.add(call.getArgument(0));
                return null;
            });
            final MatchController controller = mock(MatchController.class, CALLS_REAL_METHODS);
            final GameView gameView = mock(GameView.class);
            final Game game = mock(Game.class);
            final PlayerView selected = mock(PlayerView.class);
            final Player player = mock(Player.class);
            final PlayerController playerController = mock(PlayerController.class);
            final Set<FullControlFlag> live = EnumSet.noneOf(FullControlFlag.class);
            if (initiallyChecked) live.add(FullControlFlag.ChooseCostOrder);
            final Set<FullControlFlag> expectedSnapshot = EnumSet.noneOf(FullControlFlag.class);
            expectedSnapshot.addAll(live);
            when(controller.getGameView()).thenReturn(gameView);
            when(gameView.getGame()).thenReturn(game);
            when(game.getPlayer(selected)).thenReturn(player);
            when(player.getController()).thenReturn(playerController);
            when(playerController.getFullControl()).thenReturn(live);
            final MatchScreen screen = mock(MatchScreen.class);
            final VPlayerPanel panel = mock(VPlayerPanel.class);
            final FPopupMenu menu = mock(FPopupMenu.class);
            match.when(MatchController::getView).thenReturn(screen);
            forge.when(Forge::getCurrentScreen).thenReturn(screen);
            panels.when(() -> MatchScreen.getPlayerPanel(selected)).thenReturn(panel);
            when(panel.localToScreenX(4f)).thenReturn(14f);
            when(panel.localToScreenY(6f)).thenReturn(26f);
            final AtomicBoolean onUi = new AtomicBoolean();
            final AtomicReference<Consumer<FullControlFlag>> toggle = new AtomicReference<>();
            doAnswer(call -> {
                assertTrue(onUi.get(), "Menu construction must not generate glyphs on the game thread");
                assertEquals(call.getArgument(0), expectedSnapshot);
                toggle.set(call.getArgument(1));
                return menu;
            }).when(controller).createFullControlMenu(anySet(), any());

            controller.showFullControl(selected, 4, 6);
            verify(controller, never()).createFullControlMenu(anySet(), any());
            verifyNoInteractions(menu);
            assertEquals(uiQueue.size(), 1);
            // UI must read the snapshot, not the mutable live set.
            live.add(FullControlFlag.LayerTimestampOrder);
            onUi.set(true);
            uiQueue.remove().run();
            verify(menu).show(screen, 14f, 26f);
            toggle.get().accept(FullControlFlag.ChooseCostOrder);
            assertEquals(live.contains(FullControlFlag.ChooseCostOrder), initiallyChecked);
            assertEquals(gameQueue.size(), 1);
            onUi.set(false);
            gameQueue.remove().run();
            assertEquals(live.contains(FullControlFlag.ChooseCostOrder), !initiallyChecked);
            toggle.get().accept(FullControlFlag.ChooseCostOrder);
            gameQueue.remove().run();
            assertEquals(live.contains(FullControlFlag.ChooseCostOrder), initiallyChecked);
            assertTrue(live.contains(FullControlFlag.LayerTimestampOrder));

            // A menu left over from a previous game must not change its controller.
            toggle.get().accept(FullControlFlag.ChooseCostOrder);
            switch (invalidation) {
                case "replaced-game" -> when(controller.getGameView()).thenReturn(mock(GameView.class));
                case "cleared-game" -> when(controller.getGameView()).thenReturn(null);
                case "left-screen" -> forge.when(Forge::getCurrentScreen).thenReturn(mock(forge.screens.FScreen.class));
                case "replaced-screen" -> match.when(MatchController::getView).thenReturn(mock(MatchScreen.class));
                case "game-over" -> when(gameView.isGameOver()).thenReturn(true);
                default -> fail("Unknown case: " + invalidation);
            }
            gameQueue.remove().run();
            assertEquals(live.contains(FullControlFlag.ChooseCostOrder), initiallyChecked);
        }
    }

    @Test
    public void closedOrReplacedGameDoesNotCreateQueuedMenu() {
        try (var ui = mockStatic(FThreads.class)) {
            final ArrayDeque<Runnable> queue = new ArrayDeque<>();
            ui.when(() -> FThreads.invokeInEdtLater(any(Runnable.class))).thenAnswer(call -> {
                queue.add(call.getArgument(0));
                return null;
            });
            final MatchController controller = mock(MatchController.class, CALLS_REAL_METHODS);
            final PlayerView selected = mock(PlayerView.class);
            controller.showFullControl(selected, 0, 0);
            controller.showFullControl(null, 0, 0);
            assertTrue(queue.isEmpty());
            final GameView gameView = mock(GameView.class, RETURNS_DEEP_STUBS);
            when(controller.getGameView()).thenReturn(gameView);
            when(gameView.getGame().getPlayer(selected).getController().getFullControl())
                    .thenReturn(EnumSet.noneOf(FullControlFlag.class));
            when(selected.isAI()).thenReturn(true);
            controller.showFullControl(selected, 0, 0);
            assertTrue(queue.isEmpty());
            when(selected.isAI()).thenReturn(false);
            when(gameView.isGameOver()).thenReturn(true);
            controller.showFullControl(selected, 0, 0);
            assertTrue(queue.isEmpty(), "An already finished game must not queue a menu");
            when(gameView.isGameOver()).thenReturn(false);
            controller.showFullControl(selected, 0, 0);
            assertEquals(queue.size(), 1);
            when(controller.getGameView()).thenReturn(null);
            queue.remove().run();
            verify(controller, never()).createFullControlMenu(anySet(), any());
        }
    }

    @DataProvider
    public Object[][] invalidatedMenus() {
        return new Object[][] {{"left-screen"}, {"game-over"}, {"replaced-game"},
                {"cleared-game"}, {"missing-screen"}, {"missing-panel"}};
    }

    @Test(dataProvider = "invalidatedMenus")
    public void queuedMenuIsDiscardedWhenInvalidated(String reason) {
        try (var ui = mockStatic(FThreads.class);
             var match = mockStatic(MatchController.class);
             var panels = mockStatic(MatchScreen.class);
             var forge = mockStatic(Forge.class)) {
            final ArrayDeque<Runnable> queue = new ArrayDeque<>();
            ui.when(() -> FThreads.invokeInEdtLater(any(Runnable.class))).thenAnswer(call -> {
                queue.add(call.getArgument(0));
                return null;
            });
            final MatchController controller = mock(MatchController.class, CALLS_REAL_METHODS);
            final PlayerView selected = mock(PlayerView.class);
            final GameView gameView = mock(GameView.class, RETURNS_DEEP_STUBS);
            when(controller.getGameView()).thenReturn(gameView);
            when(gameView.getGame().getPlayer(selected).getController().getFullControl())
                    .thenReturn(EnumSet.noneOf(FullControlFlag.class));
            final MatchScreen screen = mock(MatchScreen.class);
            final FPopupMenu menu = mock(FPopupMenu.class);
            match.when(MatchController::getView).thenReturn(screen);
            forge.when(Forge::getCurrentScreen).thenReturn(screen);
            panels.when(() -> MatchScreen.getPlayerPanel(selected)).thenReturn(mock(VPlayerPanel.class));
            doReturn(menu).when(controller).createFullControlMenu(anySet(), any());
            controller.showFullControl(selected, 0, 0);
            assertEquals(queue.size(), 1);
            switch (reason) {
                // afterGameEnd retains both GameView and static MatchScreen; navigation changes.
                case "left-screen" -> forge.when(Forge::getCurrentScreen).thenReturn(mock(forge.screens.FScreen.class));
                case "game-over" -> when(gameView.isGameOver()).thenReturn(true);
                case "replaced-game" -> when(controller.getGameView()).thenReturn(mock(GameView.class));
                case "cleared-game" -> when(controller.getGameView()).thenReturn(null);
                case "missing-screen" -> match.when(MatchController::getView).thenReturn(null);
                case "missing-panel" -> panels.when(() -> MatchScreen.getPlayerPanel(selected)).thenReturn(null);
                default -> fail("Unknown case: " + reason);
            }
            queue.remove().run();
            verify(controller, never()).createFullControlMenu(anySet(), any());
            verifyNoInteractions(menu);
        }
    }

    @Test
    public void realMenuPreservesAllSixLabelsChecksAndCallbacks() throws Exception {
        final FSkinFont font = mock(FSkinFont.class);
        when(font.getBounds(any(CharSequence.class))).thenReturn(new forge.util.TextBounds());
        final forge.util.Localizer localizer = mock(forge.util.Localizer.class);
        when(localizer.getMessage(anyString())).thenAnswer(call -> call.getArgument(0));
        try (var fonts = mockStatic(FSkinFont.class);
             var forge = mockStatic(Forge.class)) {
            fonts.when(() -> FSkinFont.get(anyInt())).thenReturn(font);
            forge.when(Forge::getLocalizer).thenReturn(localizer);
            final MatchController controller = mock(MatchController.class, CALLS_REAL_METHODS);
            final String[] labels = {"lblChooseCostOrder", "lblChooseCostReductionOrder",
                    "lblNoPaymentFromManaAbility", "lblNoFreeCombatCostHandling",
                    "lblAllowPaymentStartWithMissingResources", "lblLayerTimestampOrder"};
            final FullControlFlag[] flags = {FullControlFlag.ChooseCostOrder,
                    FullControlFlag.ChooseCostReductionOrderAndVariableAmount,
                    FullControlFlag.NoPaymentFromManaAbility, FullControlFlag.NoFreeCombatCostHandling,
                    FullControlFlag.AllowPaymentStartWithMissingResources, FullControlFlag.LayerTimestampOrder};
            final Set<FullControlFlag> checked = EnumSet.of(flags[0], flags[2], flags[5]);
            final java.util.List<FullControlFlag> dispatched = new java.util.ArrayList<>();
            final FPopupMenu menu = controller.createFullControlMenu(checked, dispatched::add);
            final java.lang.reflect.Method build = menu.getClass().getDeclaredMethod("buildMenu");
            build.setAccessible(true);
            build.invoke(menu);
            assertEquals(menu.getChildCount(), 7);
            final forge.menu.FMenuItem title = (forge.menu.FMenuItem) menu.getChildAt(0);
            assertFalse(title.isEnabled());
            assertEquals(title.getText(), "- lblFullControl -");
            final java.lang.reflect.Field check = forge.menu.FCheckBoxMenuItem.class.getDeclaredField("checked");
            check.setAccessible(true);
            final java.lang.reflect.Field handler = forge.menu.FMenuItem.class.getDeclaredField("handler");
            handler.setAccessible(true);
            for (int i = 0; i < flags.length; i++) {
                final forge.menu.FCheckBoxMenuItem item = (forge.menu.FCheckBoxMenuItem) menu.getChildAt(i + 1);
                assertEquals(item.getText(), labels[i]);
                assertEquals(check.getBoolean(item), checked.contains(flags[i]));
                ((forge.toolbox.FEvent.FEventHandler) handler.get(item)).handleEvent(null);
                assertEquals(dispatched.get(i), flags[i]);
            }
            assertEquals(checked, EnumSet.of(flags[0], flags[2], flags[5]));
            verify(font, times(7)).getBounds(any(CharSequence.class));
        }
    }
}
