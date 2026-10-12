package forge.screens.home.online;

import forge.gamemodes.net.RelayRoomPresentation;
import forge.relay.RelayProtocol.RoomSnapshot;
import forge.toolbox.FOptionPane;
import forge.util.Localizer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Consumer;

/** A sortable, searchable room directory; the modal dialog does no network I/O. */
final class RelayRoomBrowser {
    private RelayRoomBrowser() { }

    static void show(final List<RoomSnapshot> rooms, final Consumer<RoomSnapshot> join,
                     final Runnable refresh) {
        final Localizer l = Localizer.getInstance();
        final String[] headers = {"lblRoomName", "lblRoomHost", "lblRoomFormat",
                "lblRoomPlayers", "lblRoomStatus", "lblRoomCode"};
        final DefaultTableModel model = new DefaultTableModel(
                java.util.Arrays.stream(headers).map(l::getMessage).toArray(), 0) {
            @Override public boolean isCellEditable(final int row, final int column) { return false; }
        };
        for (final RoomSnapshot room : rooms) {
            model.addRow(new Object[] {room.name(), room.ownerName(),
                    RelayRoomPresentation.formatLabel(room.format()),
                    room.players() + "/" + room.maxPlayers(),
                    RelayRoomPresentation.availability(room), room.roomId()});
        }
        final JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(30);
        final DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, renderer);
        table.setPreferredScrollableViewportSize(new Dimension(700, 320));
        final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        final JTextField search = new JTextField();
        search.setToolTipText(l.getMessage("lblRoomSearch"));
        final JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setOpaque(false);
        final JPanel searchPanel = new JPanel(new BorderLayout(8, 0));
        searchPanel.setOpaque(false);
        searchPanel.add(new JLabel(l.getMessage("lblRoomSearch")), BorderLayout.WEST);
        searchPanel.add(search, BorderLayout.CENTER);
        panel.add(searchPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        final FOptionPane pane = new FOptionPane(
                rooms.isEmpty() ? l.getMessage("lblNoRelayRooms") : l.getMessage("lblRoomTagNotice"),
                l.getMessage("lblBrowseRelayRooms"), null, panel,
                List.of(l.getMessage("lblJoinRelayRoom"), l.getMessage("lblRoomRefresh"),
                        l.getMessage("lblCancel")), 2);
        pane.setButtonEnabled(0, false);
        table.getSelectionModel().addListSelectionListener(e -> {
            final int row = table.getSelectedRow();
            pane.setButtonEnabled(0, row >= 0 && rooms.get(table.convertRowIndexToModel(row)).players()
                    < rooms.get(table.convertRowIndexToModel(row)).maxPlayers());
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                table.clearSelection();
                sorter.setRowFilter(new RowFilter<>() {
                    @Override public boolean include(final Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                        return RelayRoomPresentation.matches(rooms.get(entry.getIdentifier()), search.getText());
                    }
                });
            }
            @Override public void insertUpdate(final DocumentEvent e) { update(); }
            @Override public void removeUpdate(final DocumentEvent e) { update(); }
            @Override public void changedUpdate(final DocumentEvent e) { update(); }
        });
        pane.setVisible(true);
        final int result = pane.getResult();
        final int row = table.getSelectedRow();
        pane.dispose();
        if (result == 1) refresh.run();
        else if (result == 0 && row >= 0) join.accept(rooms.get(table.convertRowIndexToModel(row)));
    }
}
