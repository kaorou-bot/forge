package forge.gamemodes.net;

import forge.relay.RelayProtocol.RoomSnapshot;
import forge.util.Localizer;

import java.util.List;
import java.util.Locale;

/** Presentation-only format tags: actual deck validation remains in the game lobby. */
public final class RelayRoomPresentation {
    public static final List<String> FORMATS = List.of(
            "Constructed", "Standard", "Pioneer", "Modern", "Legacy", "Vintage",
            "Commander", "Pauper", "Draft", "Sealed", "Casual");

    private RelayRoomPresentation() { }

    public static String formatLabel(final String format) {
        return Localizer.getInstance().getMessageorUseDefault("lblRoomFormat" + format, format);
    }

    public static boolean matches(final RoomSnapshot room, final String query) {
        final String text = room.roomId() + " " + room.name() + " " + room.ownerName()
                + " " + room.format() + " " + formatLabel(room.format());
        return text.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    public static String availability(final RoomSnapshot room) {
        final Localizer l = Localizer.getInstance();
        if (room.players() >= room.maxPlayers()) return l.getMessage("lblRoomFull");
        return l.getMessage(room.passwordProtected() ? "lblRoomLocked" : "lblRoomOpen");
    }
}
