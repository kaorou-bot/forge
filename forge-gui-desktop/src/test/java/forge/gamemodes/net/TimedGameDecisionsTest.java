package forge.gamemodes.net;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CardCollection;
import forge.game.GameEntityView;
import forge.game.cost.Cost;
import forge.game.cost.CostPayment;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.zone.ZoneType;
import forge.game.phase.PhaseType;
import forge.game.player.DecisionGameEnded;
import forge.game.player.Player;
import forge.game.player.PlayerDecisionFrame;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputSelectEntitiesFromList;
import forge.gamemodes.match.input.InputPayManaOfCostPayment;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.match.input.InputBlock;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.gui.GuiRequestScope;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.net.HeadlessGuiDesktop;
import forge.net.HeadlessNetworkGuiGame;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.player.HumanCostDecision;
import forge.util.FSerializableFunction;
import forge.util.collect.FCollection;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real Game, Player, Human wrapper, Input and departure code; only GUI/user input is scripted.
 * No complete match loop, network transport or real graphical/device coverage is claimed.
 * Run in an isolated JVM with APPDATA and LOCALAPPDATA beneath -Dforge.independent.testRoot.
 */
@Test(singleThreaded = true, timeOut = 15000)
public class TimedGameDecisionsTest {
    private IGuiBase previousGui;

    @BeforeClass
    public void initializeIsolatedModelOutsideTestTimeout() {
        final String configuredRoot = System.getProperty("forge.independent.testRoot");
        Assert.assertNotNull(configuredRoot, "Explicit isolated test output root is required");
        final Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
        assertUnder(root, System.getenv("APPDATA"));
        assertUnder(root, System.getenv("LOCALAPPDATA"));
        final Path assets = Path.of("..", "forge-gui").toAbsolutePath().normalize();
        Assert.assertTrue(Files.isDirectory(assets.resolve("res")), "Run from forge-gui-desktop");
        Assert.assertFalse(Files.exists(assets.resolve("forge.profile.properties")),
                "A profile file could override isolated environment paths; inspect it before proceeding");
        previousGui = GuiBase.getInterface();
        GuiBase.setInterface(new HeadlessGuiDesktop() {
            @Override public String getAssetsDir() { return assets.toString() + java.io.File.separator; }
        });
        assertUnder(root, ForgeConstants.USER_DIR);
        assertUnder(root, ForgeConstants.CACHE_DIR);
        if (StaticData.instance() == null) {
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                preferences.setPref(FPref.MAX_LOG_FILES, "0");
                preferences.setPref(FPref.DECKGEN_CARDBASED, false);
                preferences.setPref(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER, false);
                preferences.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                return null;
            });
        }
    }

    private static void assertUnder(final Path root, final String value) {
        Assert.assertNotNull(value, "Missing isolated profile path");
        Assert.assertTrue(Path.of(value).toAbsolutePath().normalize().startsWith(root),
                "Profile/cache path must stay within the explicit test root: " + value);
    }

    @AfterClass(alwaysRun = true)
    public void restoreGui() {
        if (previousGui != null) GuiBase.setInterface(previousGui);
    }

    @DataProvider(name = "playerCounts")
    public Object[][] playerCounts() { return new Object[][] { { 2 }, { 3 }, { 8 } }; }

    @Test(dataProvider = "playerCounts")
    public void aRealInputTimeoutRemovesExactlyOnePlayer(final int count) throws Exception {
        try (Fixture f = new Fixture(count)) {
            f.start(0, false);
            if (count == 2) {
                Assert.expectThrows(DecisionGameEnded.class, () -> f.priorityWait(0));
                Assert.assertTrue(f.game.isGameOver());
            } else {
                Assert.assertNull(f.priorityWait(0));
                Assert.assertFalse(f.game.isGameOver());
                Assert.assertEquals(f.game.getPlayers().size(), count - 1);
                for (int i = 1; i < count; i++) Assert.assertTrue(f.players.get(i).isInGame());
            }
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertTrue(f.players.get(0).conceded());
            Assert.assertFalse(f.game.getPlayers().contains(f.players.get(0)));
            Assert.assertNull(f.uiFailure.get());
        }
    }

    public void survivingObjectGetsAnActualMandatoryAnswerFromAnotherPlayer() throws Exception {
        try (Fixture f = new Fixture(3)) {
            final SpellAbility source = f.stackSpell(1);
            final FCollection<Player> options = new FCollection<>(List.of(f.players.get(1), f.players.get(2)));
            f.start(0, false);
            try (PlayerDecisionFrame frame = PlayerDecisionFrame.enter(
                    PlayerDecisionFrame.Kind.RESOLVE, f.players.get(1), source)) {
                final List<Player> answer = f.humans.get(0).chooseEntitiesForEffect(
                        options, 1, 1, null, source, "Mandatory surviving choice", null, Map.of());
                Assert.assertEquals(answer.size(), 1, "Timeout is not an empty mandatory answer");
                Assert.assertTrue(options.contains(answer.get(0)));
                Assert.assertTrue(answer.get(0).isInGame());
                Assert.assertFalse(frame.isAbandoned());
            }
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertNotNull(f.game.getStack().getInstanceMatchingSpellAbilityID(source));
            Assert.assertTrue(f.acceptedInputs.get() > 0, "The replacement must actually answer an Input");
            Assert.assertNull(f.uiFailure.get());
        }
    }

    public void departedPayerDoesNotDelegatePaymentOrAbandonAnotherPlayersObject() throws Exception {
        try (Fixture f = new Fixture(3)) {
            final SpellAbility source = f.stackSpell(1);
            f.start(0, false);
            try (PlayerDecisionFrame resolve = PlayerDecisionFrame.enter(
                    PlayerDecisionFrame.Kind.RESOLVE, f.players.get(1), source)) {
                final boolean paid = TimedGameDecisions.pay(f.humans.get(0), source,
                        () -> f.localInput(0), next -> {
                            throw new AssertionError("A departed payer cannot delegate its payment");
                        });
                Assert.assertFalse(paid);
                Assert.assertSame(PlayerDecisionFrame.current(), resolve);
                Assert.assertFalse(resolve.isAbandoned());
            }
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertNotNull(f.game.getStack().getInstanceMatchingSpellAbilityID(source));
            Assert.assertNull(f.uiFailure.get());
        }
    }

    public void disconnectionBeforeReadyStillExpiresTheRealWaitingInput() throws Exception {
        try (Fixture f = new Fixture(3)) {
            f.start(0, true);
            Assert.assertNull(f.priorityWait(0));
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertEquals(f.disconnectedWaits.get(), 1);
            Assert.assertNull(f.uiFailure.get());
        }
    }

    public void repeatingAnExpiredPlayersDecisionDoesNotRepeatDeparture() throws Exception {
        try (Fixture f = new Fixture(3)) {
            f.start(0, false);
            Assert.assertNull(f.priorityWait(0));
            final List<Player> survivors = new ArrayList<>(f.game.getPlayers());
            final int shown = f.shownInputs.get();
            Assert.assertNull(f.priorityWait(0));
            Assert.assertEquals(new ArrayList<>(f.game.getPlayers()), survivors);
            Assert.assertEquals(f.shownInputs.get(), shown, "Do not reopen an expired player's prompt");
            Assert.assertNull(f.uiFailure.get());
        }
    }

    public void aSurvivingStackObjectRejectsAbortUntilRealRemoval() throws Exception {
        try (Fixture f = new Fixture(3)) {
            final SpellAbility source = f.stackSpell(0);
            final SpellAbility other = f.stackSpell(1);
            try (PlayerDecisionFrame frame = PlayerDecisionFrame.enter(
                    PlayerDecisionFrame.Kind.RESOLVE, f.players.get(0), source)) {
                Assert.expectThrows(IllegalStateException.class, frame::abort);
                f.players.get(0).concede();
                f.game.getAction().checkGameOverCondition();
                Assert.assertNull(f.game.getStack().getInstanceMatchingSpellAbilityID(source));
                Assert.assertNotNull(f.game.getStack().getInstanceMatchingSpellAbilityID(other));
                Assert.assertTrue(frame.owns(Assert.expectThrows(PlayerDecisionFrame.Aborted.class, frame::abort)));
            }
        }
    }

    public void noSessionPreservesTheOriginalLocalResult() throws Exception {
        try (Fixture f = new Fixture(2)) {
            final Object original = new Object();
            Assert.assertSame(TimedGameDecisions.choose(f.humans.get(0), null,
                    TimedGameDecisions.Purpose.CHOICE, () -> original,
                    next -> { throw new AssertionError("Untimed choice was delegated"); }), original);
            Assert.assertTrue(f.players.get(0).isInGame());
        }
    }

    @DataProvider(name = "realCosts")
    public Object[][] realCosts() {
        return new Object[][] { { "Discard<1/Card>", ZoneType.Hand },
                { "Sac<1/Creature>", ZoneType.Battlefield } };
    }

    @Test(dataProvider = "realCosts")
    public void actualCostDecisionTimeoutDoesNotPayOrDelegate(final String script, final ZoneType zone) throws Exception {
        try (Fixture f = new Fixture(3)) {
            final SpellAbility source = f.stackSpell(1);
            final Cost cost = new Cost(script, true);
            source.setPayCosts(cost);
            for (int i = 0; i < 2; i++) f.addCard("Llanowar Elves", 0, zone);
            f.start(0, false);
            try (PlayerDecisionFrame resolve = PlayerDecisionFrame.enter(
                    PlayerDecisionFrame.Kind.RESOLVE, f.players.get(1), source)) {
                final CostPayment payment = new CostPayment(cost, source);
                Assert.assertFalse(payment.payCost(new HumanCostDecision(
                        f.humans.get(0), f.players.get(0), source, true, "Independent cost timeout")));
                Assert.assertSame(PlayerDecisionFrame.current(), resolve);
                Assert.assertFalse(resolve.isAbandoned());
            }
            Assert.assertEquals(f.shownInputs.get(), 1, "No other player may pay the departed player's cost");
            Assert.assertEquals(f.acceptedInputs.get(), 0);
            Assert.assertNull(f.game.costPaymentStack.peek(), "The cost stack must unwind after timeout");
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertNotNull(f.game.getStack().getInstanceMatchingSpellAbilityID(source));
            Assert.assertNull(f.uiFailure.get());
        }
    }

    public void nestedWaitChargesDuringProcessingAndOnlyItsOwnCloseIsEffective() throws Exception {
        try (Fixture f = new Fixture(3)) {
            f.session = TimedGameDecisions.start(f.game, 5000, (human, scope) -> { }, state -> { });
            TimedGameDecisions.choose(f.humans.get(0), null, TimedGameDecisions.Purpose.PRIORITY, () -> {
                final GuiRequestScope scope = GuiRequestScope.current();
                final GuiRequestScope.WaitRegistration parent = scope.beginWait();
                parent.ready();
                Assert.assertTrue(f.snapshot(0).running());
                scope.activity().processingStarted();
                try {
                    final long paused = f.snapshot(0).remainingMillis();
                    waitMillis(150);
                    Assert.assertEquals(f.snapshot(0).remainingMillis(), paused, "Rules processing must not be charged");
                    final GuiRequestScope.WaitRegistration child = scope.beginWait();
                    final Runnable oldReady = scope.captureReadiness();
                    Assert.assertFalse(f.snapshot(0).running(), "Unshown child must not charge");
                    child.ready();
                    Assert.assertTrue(f.snapshot(0).running(), "Nested modal must charge at its processing depth");
                    waitMillis(150);
                    child.close();
                    Assert.assertTrue(f.snapshot(0).remainingMillis() < paused - 80);
                    Assert.assertFalse(f.snapshot(0).running(), "Parent remains paused until processing completes");
                    final GuiRequestScope.WaitRegistration next = scope.beginWait();
                    oldReady.run();
                    child.close();
                    Assert.assertFalse(f.snapshot(0).running(), "Retired ready must not start the next modal");
                    next.ready();
                    child.close();
                    Assert.assertTrue(f.snapshot(0).running(), "Duplicate close must not close the next modal");
                    next.close();
                } finally { scope.activity().processingFinished(); }
                Assert.assertTrue(f.snapshot(0).running(), "Parent resumes after the action returns");
                final GuiRequestScope.WaitRegistration top = scope.beginWait();
                top.ready();
                parent.close(); // Deliberately non-LIFO: identity removal must preserve top.
                Assert.assertTrue(f.snapshot(0).running());
                top.close();
                Assert.assertFalse(f.snapshot(0).running());
                return true;
            }, next -> { throw new AssertionError("Unexpected delegation"); });
        }
    }

    public void nestedBrokerPausesParentAndRestoresTheOriginalPlayerBudget() throws Exception {
        try (Fixture f = new Fixture(3)) {
            f.session = TimedGameDecisions.start(f.game, 5000, (human, scope) -> { }, state -> { });
            TimedGameDecisions.choose(f.humans.get(0), null, TimedGameDecisions.Purpose.PRIORITY, () -> {
                try (GuiRequestScope.WaitRegistration parent = GuiRequestScope.current().beginWait()) {
                    parent.ready();
                    TimedGameDecisions.choose(f.humans.get(1), null, TimedGameDecisions.Purpose.CHOICE, () -> {
                        try (GuiRequestScope.WaitRegistration child = GuiRequestScope.current().beginWait()) {
                            final long parentBudget = f.snapshot(0).remainingMillis();
                            child.ready();
                            Assert.assertFalse(f.snapshot(0).running());
                            Assert.assertTrue(f.snapshot(1).running());
                            waitMillis(150);
                            Assert.assertEquals(f.snapshot(0).remainingMillis(), parentBudget);
                            Assert.assertTrue(f.snapshot(1).remainingMillis() < 4950);
                            return true;
                        }
                    }, next -> { throw new AssertionError("Unexpected delegation"); });
                    Assert.assertTrue(f.snapshot(0).running());
                    Assert.assertFalse(f.snapshot(1).running());
                    return true;
                }
            }, next -> { throw new AssertionError("Unexpected delegation"); });
        }
    }

    public void actualManaInputDirectOneTimesOutInsideProcessingAndDrainsItsAction() throws Exception {
        try (Fixture f = new Fixture(3)) {
            final SpellAbility source = f.stackSpell(1);
            final Card crystal = f.addCard("Crystal Vein", 0, ZoneType.Battlefield);
            final PlayerControllerHuman human = f.humans.get(0);
            final AtomicInteger modals = new AtomicInteger();
            final HeadlessNetworkGuiGame scriptedGui = new HeadlessNetworkGuiGame() {
                @Override public boolean isLibgdxPort() { return true; }
                @Override public <T> T one(final String message, final List<T> choices,
                                           final FSerializableFunction<T, String> display) {
                    Assert.assertTrue(choices.size() > 1, "Real Crystal Vein must reach the direct one() branch");
                    modals.incrementAndGet();
                    // Script visibility/dismissal at the same production synchronous choice boundary.
                    // This does not instantiate a native FDialog or claim graphical coverage.
                    return FThreads.callInEdtAndWaitChoice(() -> {
                        final GuiRequestScope scope = GuiRequestScope.current();
                        final CountDownLatch dismissed = new CountDownLatch(1);
                        try (GuiRequestScope.Registration listener = scope.onCancel(dismissed::countDown)) {
                            scope.captureReadiness().run();
                            Assert.assertTrue(f.snapshot(0).running(), "Modal is actionable despite parent processing");
                            Assert.assertTrue(dismissed.await(3, TimeUnit.SECONDS), "Nested mana modal failed to expire");
                            return choices.get(0); // A dismissal default must never escape the boundary.
                        }
                    });
                }
            };
            scriptedGui.setGameView(f.game.getView());
            scriptedGui.setOriginalGameController(f.players.get(0).getView(), human);
            human.setGui(scriptedGui);
            f.session = TimedGameDecisions.start(f.game, 500, (controller, scope) -> {
                try {
                    scope.ready();
                    controller.getInputQueue().getInput().selectCard(crystal, null, null);
                } catch (final Throwable failure) {
                    f.uiFailure.set(failure);
                    scope.cancel(GuiRequestScope.Reason.CANCELLED);
                }
            }, state -> { });
            final boolean paid = TimedGameDecisions.pay(human, source, () -> {
                final InputPayManaOfCostPayment input = new InputPayManaOfCostPayment(human,
                        new ManaCostBeingPaid(source.getHostCard().getManaCost()), source, f.players.get(0), null, true) {
                    @Override public void showMessage() { } // Scripted rendering, production selection/action/cleanup.
                };
                input.showAndWait();
                throw new AssertionError("Timed out mana input returned a successful payment path");
            }, next -> { throw new AssertionError("Mana payment cannot be delegated"); });
            Assert.assertFalse(paid);
            Assert.assertEquals(modals.get(), 1);
            Assert.assertNull(f.uiFailure.get());
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertNull(human.getInputQueue().getInput());
            Assert.assertNull(source.getManaCostBeingPaid());
            Assert.assertNull(f.players.get(0).getPaidForSA());
            Assert.assertNotNull(f.game.getStack().getInstanceMatchingSpellAbilityID(source));
        }
    }

    @Test(dataProvider = "playerCounts")
    public void newRealGameIsIsolatedFromRetiredReadyAndClose(final int count) throws Exception {
        final AtomicReference<GuiRequestScope.WaitRegistration> retired = new AtomicReference<>();
        final AtomicReference<Runnable> ready = new AtomicReference<>();
        try (Fixture old = new Fixture(count)) {
            old.session = TimedGameDecisions.start(old.game, 5000, (human, scope) -> { }, state -> { });
            TimedGameDecisions.choose(old.humans.get(0), null, TimedGameDecisions.Purpose.PRIORITY, () -> {
                retired.set(GuiRequestScope.current().beginWait());
                ready.set(GuiRequestScope.current().captureReadiness());
                retired.get().ready();
                waitMillis(100);
                return true;
            }, next -> { throw new AssertionError("Unexpected delegation"); });
            old.session.close();
            try (Fixture fresh = new Fixture(count)) {
                fresh.session = TimedGameDecisions.start(fresh.game, 5000, (human, scope) -> { }, state -> { });
                Assert.assertEquals(fresh.players.get(0).getId(), old.players.get(0).getId());
                TimedGameDecisions.choose(fresh.humans.get(0), null, TimedGameDecisions.Purpose.PRIORITY, () -> {
                    try (GuiRequestScope.WaitRegistration current = GuiRequestScope.current().beginWait()) {
                        ready.get().run();
                        retired.get().ready();
                        retired.get().close();
                        old.session.connected(old.players.get(0).getId(), false);
                        Assert.assertEquals(fresh.snapshot(0).remainingMillis(), 5000L);
                        Assert.assertFalse(fresh.snapshot(0).running());
                        current.ready();
                        Assert.assertTrue(fresh.snapshot(0).running());
                        Assert.assertEquals(fresh.game.getPlayers().size(), count);
                        return true;
                    }
                }, next -> { throw new AssertionError("Unexpected delegation"); });
            }
        }
    }

    public void fourPlayerTeamsContinueUntilTheLastOpponentOnATeamLeaves() throws Exception {
        try (Fixture f = new Fixture(4, true)) {
            Assert.assertFalse(f.players.get(0).isOpponentOf(f.players.get(2)));
            Assert.assertTrue(f.players.get(0).isOpponentOf(f.players.get(1)));
            f.session = TimedGameDecisions.start(f.game, 500, (human, scope) -> scope.ready(), state -> { });
            Assert.assertNull(f.priorityWait(0));
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertFalse(f.players.get(2).hasLost(), "One timeout must not concede the teammate");
            Assert.assertEquals(f.game.getPlayers().size(), 3);
            Assert.assertFalse(f.game.isGameOver());
            Assert.expectThrows(DecisionGameEnded.class, () -> f.priorityWait(2));
            Assert.assertTrue(f.game.isGameOver());
            Assert.assertTrue(f.players.get(2).hasLost());
            Assert.assertFalse(f.players.get(1).hasLost());
            Assert.assertFalse(f.players.get(3).hasLost());
        }
    }

    public void attackerTimeoutUnwindsTheActualPhaseActionAndRemovesItsCreature() throws Exception {
        try (Fixture f = new Fixture(3)) {
            final Card attacker = f.addCard("Llanowar Elves", 0, ZoneType.Battlefield);
            attacker.setSickness(false);
            final Combat combat = new Combat(f.players.get(0));
            f.game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, f.players.get(0), false);
            f.game.getPhaseHandler().setCombat(combat);
            Assert.assertTrue(CombatUtil.canAttack(f.players.get(0)));
            final AtomicInteger prompts = new AtomicInteger();
            f.session = TimedGameDecisions.start(f.game, 500, (human, scope) -> {
                try {
                    final Input input = human.getInputQueue().getInput();
                    Assert.assertTrue(input instanceof InputAttack);
                    input.selectCard(attacker, null, null); // Partial declaration before timeout.
                    Assert.assertTrue(combat.getAttackers().contains(attacker));
                    prompts.incrementAndGet();
                    scope.ready();
                } catch (final Throwable failure) {
                    f.uiFailure.set(failure);
                    scope.cancel(GuiRequestScope.Reason.CANCELLED);
                }
            }, state -> { });
            invokeCombatPhaseAction(f, "declareAttackersTurnBasedAction");
            Assert.assertEquals(prompts.get(), 1);
            Assert.assertNull(f.uiFailure.get());
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertEquals(f.game.getPlayers().size(), 2);
            Assert.assertFalse(f.game.isGameOver());
            Assert.assertFalse(combat.getAttackers().contains(attacker));
            Assert.assertNull(f.humans.get(0).getInputQueue().getInput());
            Assert.assertNull(PlayerDecisionFrame.current());
        }
    }

    public void blockerTimeoutStillLetsTheNextDefenderMakeARealBlock() throws Exception {
        try (Fixture f = new Fixture(3)) {
            final Card firstAttack = f.addCard("Llanowar Elves", 0, ZoneType.Battlefield);
            final Card secondAttack = f.addCard("Llanowar Elves", 0, ZoneType.Battlefield);
            final Card firstBlock = f.addCard("Llanowar Elves", 1, ZoneType.Battlefield);
            final Card secondBlock = f.addCard("Llanowar Elves", 2, ZoneType.Battlefield);
            firstAttack.setSickness(false);
            secondAttack.setSickness(false);
            final Combat combat = new Combat(f.players.get(0));
            f.game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.players.get(0), false);
            f.game.getPhaseHandler().setCombat(combat);
            combat.addAttacker(firstAttack, f.players.get(1));
            combat.addAttacker(secondAttack, f.players.get(2));
            final List<Integer> asked = new ArrayList<>();
            f.session = TimedGameDecisions.start(f.game, 500, (human, scope) -> {
                try {
                    final Input input = human.getInputQueue().getInput();
                    Assert.assertTrue(input instanceof InputBlock);
                    final int index = f.humans.indexOf(human);
                    asked.add(index);
                    Assert.assertTrue(index == 1 || index == 2);
                    input.selectCard(index == 1 ? firstBlock : secondBlock, null, null);
                    scope.ready();
                    if (index == 2) input.selectButtonOK();
                } catch (final Throwable failure) {
                    f.uiFailure.set(failure);
                    scope.cancel(GuiRequestScope.Reason.CANCELLED);
                }
            }, state -> { });
            invokeCombatPhaseAction(f, "declareBlockersTurnBasedAction");
            Assert.assertNull(f.uiFailure.get());
            Assert.assertEquals(asked, List.of(1, 2), "One defender's abort cannot exit the entire blockers step");
            Assert.assertTrue(f.players.get(1).hasLost());
            Assert.assertTrue(f.players.get(2).isInGame());
            Assert.assertFalse(combat.getAllBlockers().contains(firstBlock));
            Assert.assertTrue(combat.getBlockers(secondAttack).contains(secondBlock));
            Assert.assertNull(f.humans.get(1).getInputQueue().getInput());
            Assert.assertNull(f.humans.get(2).getInputQueue().getInput());
            Assert.assertNull(PlayerDecisionFrame.current());
        }
    }

    @DataProvider(name = "cleanupPorts")
    public Object[][] cleanupPorts() { return new Object[][] { { false }, { true } }; }

    @Test(dataProvider = "cleanupPorts")
    public void cleanupDiscardTimeoutDoesNotAskAnotherPlayerAndClearsTemporaryCards(final boolean mobile) throws Exception {
        try (Fixture f = new Fixture(3)) {
            for (int i = 0; i < 8; i++) f.addCard("Llanowar Elves", 0, ZoneType.Hand);
            final AtomicInteger prompts = new AtomicInteger();
            f.setGui(0, new HeadlessNetworkGuiGame() {
                @Override public boolean isLibgdxPort() { return mobile; }
                @Override public <T> List<T> many(final String title, final String caption, final int min,
                        final int max, final List<T> choices, final List<T> selected, final CardView card) {
                    prompts.incrementAndGet();
                    return expireChoice(List.of());
                }
            });
            f.session = TimedGameDecisions.start(f.game, 500, (human, scope) -> {
                Assert.assertSame(human, f.humans.get(0));
                prompts.incrementAndGet();
                scope.ready();
            }, state -> { });
            Assert.assertTrue(f.humans.get(0).chooseCardsToDiscardToMaximumHandSize(1).isEmpty());
            Assert.assertEquals(prompts.get(), 1);
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertEquals(f.game.getPlayers().size(), 2);
            Assert.assertNull(PlayerDecisionFrame.current());
            Assert.assertEquals(temporaryCardCount(f.humans.get(0)), 0, "Aborted cleanup must release temporary card visibility");
        }
    }

    public void revealBroadcastTimeoutClosesDisplayAndContinuesWithoutConcurrentModification() throws Exception {
        final boolean previous = FModel.getPreferences().getPrefBoolean(FPref.UI_SELECT_FROM_CARD_DISPLAYS);
        FModel.getPreferences().setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, true);
        try (Fixture f = new Fixture(3)) {
            final Card secret = f.addCard("Divination", 2, ZoneType.Hand);
            final AtomicInteger shown = new AtomicInteger();
            final AtomicInteger hidden = new AtomicInteger();
            for (int i = 0; i < 2; i++) f.setGui(i, new HeadlessNetworkGuiGame() {
                @Override public void showRevealedCards(final Iterable<CardView> cards) { shown.incrementAndGet(); }
                @Override public void hideRevealedCards() { hidden.incrementAndGet(); }
            });
            Assert.assertFalse(secret.mayPlayerLook(f.players.get(0)));
            f.session = TimedGameDecisions.start(f.game, 500, (human, scope) -> {
                scope.ready();
                if (human == f.humans.get(1)) human.getInputQueue().getInput().selectButtonOK();
            }, state -> { });
            f.game.getAction().reveal(new CardCollection(secret), ZoneType.Hand, f.players.get(2), true, "Notice", false);
            Assert.assertEquals(shown.get(), 2);
            Assert.assertEquals(hidden.get(), 2);
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertTrue(f.players.get(1).isInGame());
            Assert.assertFalse(secret.mayPlayerLook(f.players.get(0)));
            Assert.assertFalse(secret.mayPlayerLook(f.players.get(1)));
            Assert.assertEquals(temporaryCardCount(f.humans.get(0)), 0);
            Assert.assertEquals(temporaryCardCount(f.humans.get(1)), 0);
        } finally { FModel.getPreferences().setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, previous); }
    }

    public void notifyBroadcastTimeoutDoesNotDelegateOrSkipTheSurvivingRecipients() throws Exception {
        try (Fixture f = new Fixture(3)) {
            final List<Integer> recipients = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final int index = i;
                f.setGui(i, new HeadlessNetworkGuiGame() {
                    @Override public void message(final String message, final String title) {
                        recipients.add(index);
                        if (index == 0) expireChoice(null);
                    }
                });
            }
            f.session = TimedGameDecisions.start(f.game, 500, (human, scope) -> { }, state -> { });
            f.game.getAction().notifyOfValue(null, null, "Independent broadcast", null);
            Assert.assertEquals(recipients, List.of(0, 1, 2));
            Assert.assertTrue(f.players.get(0).hasLost());
            Assert.assertEquals(f.game.getPlayers().size(), 2);
        }
    }

    @DataProvider(name = "combatDamageTimeouts")
    public Object[][] combatDamageTimeouts() { return new Object[][] { { -1 }, { 0 }, { 1 } }; }

    @Test(dataProvider = "combatDamageTimeouts")
    public void realCombatAssignmentsRespectDepartedSourcesAndKeepOtherDefendersDamage(final int expire) throws Exception {
        try (Fixture f = new Fixture(3)) {
            final Combat combat = new Combat(f.players.get(0));
            f.game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.players.get(0), false);
            f.game.getPhaseHandler().setCombat(combat);
            f.players.get(1).addChangedKeywords(List.of("You assign combat damage of each creature attacking you."),
                    List.of(), f.game.getNextTimestamp(), 0);
            final List<Card> attackers = new ArrayList<>();
            final List<Card> targets = new ArrayList<>();
            for (int defender = 1; defender <= 2; defender++) {
                final Card attacker = f.addCard("Hill Giant", 0, ZoneType.Battlefield);
                attackers.add(attacker);
                combat.addAttacker(attacker, f.players.get(defender));
                for (int b = 0; b < 2; b++) {
                    final Card blocker = f.addCard("Llanowar Elves", defender, ZoneType.Battlefield);
                    targets.add(blocker);
                    combat.addBlocker(attacker, blocker);
                }
                combat.setBlocked(attacker, true);
            }
            combat.orderBlockersForDamageAssignment();
            combat.orderAttackersForDamageAssignment();
            final List<Integer> asked = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final int index = i;
                f.setGui(i, new HeadlessNetworkGuiGame() {
                    @Override public Map<CardView, Integer> assignCombatDamage(final CardView attacker,
                            final List<CardView> blockers, final int damage, final GameEntityView defender,
                            final boolean override, final boolean maySkip) {
                        asked.add(index);
                        if (index == expire) return expireChoice(Map.of());
                        return Map.of(blockers.get(0), damage);
                    }
                });
            }
            if (expire >= 0) f.session = TimedGameDecisions.start(f.game, 500, (human, scope) -> { }, state -> { });
            combat.assignCombatDamage(false);
            Assert.assertNull(PlayerDecisionFrame.current());
            if (expire == 0) {
                Assert.assertTrue(f.players.get(0).hasLost());
                for (final Card target : targets) Assert.assertTrue(target.getAssignedDamageMap().isEmpty(),
                        "Earlier assigned damage from the departed attacker must be removed");
                Assert.assertTrue(combat.getAttackers().isEmpty());
            } else {
                if (expire == 1) {
                    Assert.assertTrue(f.players.get(1).hasLost());
                    Assert.assertFalse(combat.getAttackers().contains(attackers.get(0)));
                    Assert.assertEquals(asked.stream().filter(i -> i == 1).count(), 1L, "Do not delegate the removed creature");
                } else {
                    Assert.assertTrue(f.players.get(1).isInGame());
                    Assert.assertTrue(targets.get(0).getAssignedDamageMap().containsKey(attackers.get(0)));
                }
                Assert.assertTrue(asked.contains(0), "Surviving assignment must use its actual controller");
                Assert.assertTrue(targets.get(2).getAssignedDamageMap().containsKey(attackers.get(1)));
                combat.dealAssignedDamage();
                Assert.assertEquals(targets.get(2).getDamage(), 3);
            }
            Assert.assertTrue(f.players.get(2).isInGame());
        }
    }

    /** Scripted modal cancellation through the production wait boundary, not visible FDialog coverage. */
    private static <T> T expireChoice(final T dismissalValue) {
        return FThreads.callInEdtAndWaitChoice(() -> {
            final GuiRequestScope scope = GuiRequestScope.current();
            final CountDownLatch cancelled = new CountDownLatch(1);
            try (GuiRequestScope.Registration registration = scope.onCancel(cancelled::countDown)) {
                scope.captureReadiness().run();
                Assert.assertTrue(cancelled.await(3, TimeUnit.SECONDS), "Timed choice did not expire");
                return dismissalValue;
            }
        });
    }

    private static int temporaryCardCount(final PlayerControllerHuman human) throws Exception {
        final java.lang.reflect.Field field = PlayerControllerHuman.class.getDeclaredField("tempShownCards");
        field.setAccessible(true);
        return ((java.util.Collection<?>) field.get(human)).size();
    }

    /** Invoke the real turn-based phase action without running an unbounded match loop. */
    private static void invokeCombatPhaseAction(final Fixture f, final String name) throws Exception {
        final java.lang.reflect.Method method = f.game.getPhaseHandler().getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        try { method.invoke(f.game.getPhaseHandler()); }
        catch (final java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }

    private static void waitMillis(final long millis) {
        try { TimeUnit.MILLISECONDS.sleep(millis); }
        catch (final InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static final class Fixture implements AutoCloseable {
        private final Game game;
        private final List<Player> players;
        private final List<PlayerControllerHuman> humans = new ArrayList<>();
        private final AtomicReference<Throwable> uiFailure = new AtomicReference<>();
        private final AtomicInteger shownInputs = new AtomicInteger();
        private final AtomicInteger acceptedInputs = new AtomicInteger();
        private final AtomicInteger disconnectedWaits = new AtomicInteger();
        private volatile TimedGameDecisions session;

        private Fixture(final int count) { this(count, false); }

        private Fixture(final int count, final boolean teams) {
            final List<RegisteredPlayer> registered = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final RegisteredPlayer player = new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerHuman("Clock " + i));
                if (teams) player.setTeamNumber(i % 2);
                registered.add(player);
            }
            final GameRules rules = new GameRules(GameType.Constructed);
            game = new Game(registered, rules, new Match(rules, registered, "Independent clock test"));
            players = new ArrayList<>(game.getPlayers());
            game.setAge(GameStage.Play);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, players.get(0));
            for (int i = 0; i < count; i++) {
                final Player player = players.get(i);
                player.setLife(20, null);
                final PlayerControllerHuman human = (PlayerControllerHuman) player.getController();
                final HeadlessNetworkGuiGame gui = new HeadlessNetworkGuiGame();
                gui.setGameView(game.getView());
                gui.setOriginalGameController(player.getView(), human);
                human.setGui(gui);
                humans.add(human);
            }
        }

        private void start(final int expireIndex, final boolean disconnectBeforeReady) {
            session = TimedGameDecisions.start(game, 500, (human, scope) -> {
                try {
                    shownInputs.incrementAndGet();
                    if (human.getOperationClockPlayer() == players.get(expireIndex)) {
                        if (disconnectBeforeReady) {
                            disconnectedWaits.incrementAndGet();
                            session.connected(human.getOperationClockPlayer().getId(), false);
                        } else scope.ready();
                        return;
                    }
                    scope.ready();
                    final Input input = human.getInputQueue().getInput();
                    Assert.assertTrue(input instanceof InputSelectEntitiesFromList<?>, "Unexpected fixture input");
                    final InputSelectEntitiesFromList<?> selection = (InputSelectEntitiesFromList<?>) input;
                    final GameEntity first = selection.getValidChoices().get(0);
                    if (first instanceof Player player) input.selectPlayer(player, null);
                    else if (first instanceof Card card) input.selectCard(card, null, null);
                    else throw new AssertionError("Unhandled fixture candidate: " + first);
                    acceptedInputs.incrementAndGet();
                    input.selectButtonOK();
                } catch (final Throwable failure) {
                    uiFailure.compareAndSet(null, failure);
                    scope.cancel(GuiRequestScope.Reason.CANCELLED);
                }
            }, state -> { });
        }

        private Boolean priorityWait(final int index) {
            return TimedGameDecisions.choose(humans.get(index), null, TimedGameDecisions.Purpose.PRIORITY,
                    () -> localInput(index), next -> {
                        throw new AssertionError("A departed priority holder cannot delegate an action");
                    });
        }

        private boolean localInput(final int index) {
            final InputSelectEntitiesFromList<Player> input = new InputSelectEntitiesFromList<>(humans.get(index),
                    1, 1, new FCollection<>(game.getPlayers()));
            input.showAndWait();
            return !input.getSelected().isEmpty();
        }

        private SpellAbility stackSpell(final int owner) {
            final Card card = Card.fromPaperCard(StaticData.instance().getCommonCards().getCard("Divination"),
                    players.get(owner));
            game.getStackZone().add(card);
            final SpellAbility ability = card.getFirstSpellAbility();
            ability.setActivatingPlayer(players.get(owner));
            card.setCastSA(ability);
            game.getStack().add(ability);
            Assert.assertNotNull(game.getStack().getInstanceMatchingSpellAbilityID(ability));
            return ability;
        }

        private Card addCard(final String name, final int owner, final ZoneType zone) {
            final Card card = Card.fromPaperCard(StaticData.instance().getCommonCards().getCard(name), players.get(owner));
            players.get(owner).getZone(zone).add(card);
            return card;
        }

        private void setGui(final int index, final HeadlessNetworkGuiGame gui) {
            gui.setGameView(game.getView());
            gui.setOriginalGameController(players.get(index).getView(), humans.get(index));
            humans.get(index).setGui(gui);
        }

        /** Read-only accounting observation; never replace the production monotonic clock or Activity. */
        private PlayerOperationClock.Snapshot snapshot(final int player) {
            try {
                final java.lang.reflect.Field field = TimedGameDecisions.class.getDeclaredField("clock");
                field.setAccessible(true);
                return ((PlayerOperationClock) field.get(session)).snapshot(players.get(player).getId());
            } catch (final ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }

        @Override public void close() throws Exception {
            if (session != null) session.close();
            // Drain this fixture's queued Swing input cleanup before another fixture starts.
            SwingUtilities.invokeAndWait(() -> { });
            game.clearCaches();
        }
    }
}
