package forge.gamemodes.net;

import forge.game.GameEntity;
import forge.game.GameEntityCounterTable;
import forge.game.card.CounterEnumType;
import forge.game.cost.Cost;
import forge.game.cost.CostBlight;
import forge.game.cost.CostPutCounter;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.OptionalCostValue;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.ReplyEvent;
import forge.util.Lang;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.*;

public class OptionalCostWireTest {
    @DataProvider
    public Object[][] counterCosts() {
        return new Object[][] {{"Blight<1>"}, {"AddCounter<1/P1P1/Creature.YouCtrl>"}, {"AddCounter<1/LOYALTY>"}};
    }

    @Test(dataProvider = "counterCosts")
    public void counterCostChoiceAndReplyRoundTrip(String script) throws Exception {
        Lang.createInstance("en-US");
        OptionalCostValue original = new OptionalCostValue(OptionalCost.Generic, new Cost(script, false));
        CostPutCounter originalPart = counterPart(original);
        GameEntityCounterTable originalTable = table(originalPart);
        originalTable.put(null, mock(GameEntity.class), CounterEnumType.M1M1, 1);

        ArrayList<OptionalCostValue> choices = new ArrayList<>();
        choices.add(original);
        GuiGameEvent request = new GuiGameEvent(ProtocolMethod.getChoices, "Optional costs", 0, 1,
                choices, null, null);
        GuiGameEvent received = (GuiGameEvent) roundTrip(request);
        assertEquals(received.getMethod(), ProtocolMethod.getChoices);
        OptionalCostValue offered = (OptionalCostValue) ((List<?>) received.getObjects()[3]).get(0);
        assertEquals(offered.toString(), original.toString());
        assertEquals(offered.getType(), original.getType());
        assertEquals(counterPart(offered).getAmount(), originalPart.getAmount());
        assertEquals(counterPart(offered).getCounter(), originalPart.getCounter());
        assertEquals(counterPart(offered).getType(), originalPart.getType());
        assertTrue(table(counterPart(offered)).isEmpty(), "Server payment state must not cross the wire");
        assertFalse(originalTable.isEmpty(), "Sending must not mutate the host's payment state");

        ArrayList<OptionalCostValue> selected = new ArrayList<>();
        selected.add(offered);
        ReplyEvent reply = (ReplyEvent) roundTrip(new ReplyEvent(request.getId(), selected));
        assertEquals(reply.getIndex(), request.getId());
        OptionalCostValue returned = (OptionalCostValue) ((List<?>) reply.getReply()).get(0);
        CostPutCounter returnedPart = counterPart(returned);
        assertNotSame(table(returnedPart), table(counterPart(offered)));
        assertTrue(table(returnedPart).isEmpty());
        // Deserialization must rebuild payment state, not leave a null transient field.
        table(returnedPart).put(null, mock(GameEntity.class), CounterEnumType.M1M1, 1);
        returnedPart.resetLists();
        assertTrue(table(returnedPart).isEmpty());
        if (script.startsWith("Blight")) {
            assertTrue(returnedPart instanceof CostBlight);
        }
    }

    private static CostPutCounter counterPart(OptionalCostValue value) {
        return value.getCost().getCostParts().stream().filter(CostPutCounter.class::isInstance)
                .map(CostPutCounter.class::cast).findFirst().orElseThrow();
    }

    private static GameEntityCounterTable table(CostPutCounter cost) throws Exception {
        Field field = CostPutCounter.class.getDeclaredField("counterTable");
        field.setAccessible(true);
        return (GameEntityCounterTable) field.get(cost);
    }

    private static Object roundTrip(Serializable event) {
        EmbeddedChannel channel = new EmbeddedChannel(new CompatibleObjectEncoder(null),
                new CompatibleObjectDecoder(10 * 1024 * 1024, ClassResolvers.cacheDisabled(null)));
        try {
            assertTrue(channel.writeOutbound(event));
            assertTrue(channel.writeInbound((Object) channel.readOutbound()));
            Object decoded = channel.readInbound();
            assertNotNull(decoded);
            return decoded;
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
