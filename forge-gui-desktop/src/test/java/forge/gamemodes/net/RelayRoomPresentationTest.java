package forge.gamemodes.net;

import forge.relay.RelayProtocol;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RelayRoomPresentationTest {
    private RelayProtocol.RoomSnapshot room(final int players) {
        return new RelayProtocol.RoomSnapshot("123456", "中文房间", "Alice", "Commander",
                "forge-cn-net-1", players, 4, true);
    }

    @Test
    public void searchUsesFieldsAndTreatsPunctuationLiterally() {
        forge.net.TestUtils.ensureFModelInitialized();
        Assert.assertTrue(RelayRoomPresentation.matches(room(2), " 中文 "));
        Assert.assertTrue(RelayRoomPresentation.matches(room(2), "ALICE"));
        Assert.assertTrue(RelayRoomPresentation.matches(room(2), "123456"));
        Assert.assertTrue(RelayRoomPresentation.matches(room(2), "commander"));
        Assert.assertFalse(RelayRoomPresentation.matches(room(2), ".*"));
        Assert.assertTrue(RelayRoomPresentation.matches(room(2), ""));
    }

    @Test
    public void fullRoomIsNotAdvertisedAsOpen() {
        forge.net.TestUtils.ensureFModelInitialized();
        Assert.assertNotEquals(RelayRoomPresentation.availability(room(4)),
                RelayRoomPresentation.availability(room(2)));
    }

    @Test
    public void unknownFormatsRemainVisibleAndTagsFitExistingProtocol() {
        forge.net.TestUtils.ensureFModelInitialized();
        Assert.assertEquals(RelayRoomPresentation.formatLabel("Custom format"), "Custom format");
        Assert.assertEquals(RelayRoomPresentation.FORMATS.stream().distinct().count(),
                (long) RelayRoomPresentation.FORMATS.size());
        for (final String format : RelayRoomPresentation.FORMATS) {
            Assert.assertTrue(format.length() <= 64);
        }
    }
}
