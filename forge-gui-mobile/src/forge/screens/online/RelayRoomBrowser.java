package forge.screens.online;

import com.badlogic.gdx.utils.Align;
import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gamemodes.net.RelayRoomPresentation;
import forge.gui.FThreads;
import forge.relay.RelayProtocol.RoomSnapshot;
import forge.toolbox.FList;
import forge.toolbox.FOptionPane;
import forge.util.Utils;

import java.util.List;
import java.util.function.Consumer;

/** Touch-sized two-line rows, without the old narrow combo-box picker. */
final class RelayRoomBrowser {
    private RelayRoomBrowser() { }

    static void show(final List<RoomSnapshot> rooms, final Consumer<RoomSnapshot> join,
                     final Runnable refresh) {
        FThreads.invokeInEdtNowOrLater(() -> {
            final FOptionPane[] pane = new FOptionPane[1];
            final FList<RoomSnapshot> list = new FList<>(rooms);
            list.setHeight(Math.min(Forge.getScreenHeight() * 0.45f, Utils.scale(400)));
            list.setListItemRenderer(new FList.ListItemRenderer<>() {
                @Override public float getItemHeight() { return Utils.AVG_FINGER_HEIGHT * 1.6f; }
                @Override public boolean tap(final Integer index, final RoomSnapshot room,
                        final float x, final float y, final int count) {
                    if (room.players() >= room.maxPlayers()) {
                        FOptionPane.showMessageDialog(Forge.getLocalizer().getMessage("lblNetworkRoomFull"));
                        return true;
                    }
                    pane[0].hide();
                    FThreads.invokeInBackgroundThread(() -> join.accept(room));
                    return true;
                }
                @Override public void drawValue(final Graphics g, final Integer index,
                        final RoomSnapshot room, final FSkinFont font, final FSkinColor foreColor,
                        final FSkinColor backColor, final boolean pressed,
                        final float x, final float y, final float w, final float h) {
                    final String first = room.name() + "  [" + room.roomId() + "]  "
                            + room.players() + "/" + room.maxPlayers();
                    final String second = RelayRoomPresentation.formatLabel(room.format()) + " · "
                            + room.ownerName() + " · " + RelayRoomPresentation.availability(room);
                    g.drawText(first, font, foreColor, x, y, w, h / 2, false, Align.left, true);
                    g.drawText(second, FSkinFont.get(12), foreColor, x, y + h / 2, w, h / 2,
                            false, Align.left, true);
                }
            });
            pane[0] = new FOptionPane(Forge.getLocalizer().getMessage(
                    rooms.isEmpty() ? "lblNoRelayRooms" : "lblRoomTapToJoin"),
                    null, Forge.getLocalizer().getMessage("lblBrowseRelayRooms"), null, list,
                    List.of(Forge.getLocalizer().getMessage("lblRoomRefresh"),
                            Forge.getLocalizer().getMessage("lblCancel")), 1,
                    result -> { if (result == 0) refresh.run(); });
            pane[0].show();
        });
    }
}
