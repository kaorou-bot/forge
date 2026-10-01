package forge.screens.match.layout;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;

/** Geometry editor, not a second game renderer. Drafts never overwrite installed packages. */
public final class MatchSkinEditor {
    private MatchSkinEditor() { }
    static MatchUiLayout geometryDraft(JsonObject root, MatchUiLayout original) {
        // Reuse decoded assets: bundled ZIP file systems close after the initial read.
        final var geometry = root.deepCopy(); geometry.getAsJsonObject("scene").remove("appearance");
        final var parsed = MatchUiLayout.read(new StringReader(geometry.toString()));
        final var theme = original.scene().appearance();
        final var variants = parsed.experience().variants().stream().map(v -> new MatchSkinExperience.Variant(v.id(),v.maxWidth(),v.minPlayers(),v.maxPlayers(),
                new MatchUiLayout(v.layout().id(),v.layout().regions(),v.layout().fieldLayout(),v.layout().scene().withAppearance(theme),v.layout().cards()))).toList();
        return new MatchUiLayout(parsed.id(),parsed.regions(),parsed.fieldLayout(),parsed.scene().withAppearance(theme),parsed.cards(),
                new MatchSkinExperience(root,original.experience().assets(),parsed.experience().defaults(),variants));
    }
    public static void show(DesktopMatchUi ui) {
        if (ui.template() == null || ui.template().experience().source() == null) { return; }
        final var original = ui.template();
        final var history = new ArrayDeque<JsonObject>();
        final JsonObject[] draft = {original.experience().source()};
        final var dialog = new JDialog((java.awt.Frame) null, "皮肤布局制作 · 几何预览（非游戏画面）", false);
        final var preset = new JComboBox<String>(); preset.addItem("base");
        original.experience().variants().forEach(v -> preset.addItem(v.id()));
        preset.setSelectedItem(ui.variant());
        final var status = new JLabel("拖动移动；右下角拖动缩放；8 像素吸附。红框/错误不会应用。");
        final class Canvas extends JPanel {
            private String selected; private Point start; private Rectangle initial; private Rectangle drag; private boolean resize;
            Canvas() {
                setBackground(new Color(0x101C26)); setPreferredSize(new java.awt.Dimension(1100, 660));
                final var mouse = new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        selected = null;
                        for (var entry : boxes().entrySet()) {
                            if (entry.getValue().contains(e.getPoint())) { selected = entry.getKey(); initial = entry.getValue(); break; }
                        }
                        if (selected != null) { start = e.getPoint(); resize = Math.abs(e.getX() - initial.getMaxX()) < 12 && Math.abs(e.getY() - initial.getMaxY()) < 12; }
                        repaint();
                    }
                    @Override public void mouseDragged(MouseEvent e) {
                        if (selected == null) { return; }
                        final int dx = Math.round((e.getX() - start.x) / 8f) * 8, dy = Math.round((e.getY() - start.y) / 8f) * 8;
                        drag = new Rectangle(resize ? initial.x : initial.x + dx, resize ? initial.y : initial.y + dy,
                                resize ? Math.max(8, initial.width + dx) : initial.width, resize ? Math.max(8, initial.height + dy) : initial.height);
                        repaint();
                    }
                    @Override public void mouseReleased(MouseEvent e) {
                        if (selected == null || drag == null) { return; }
                        try {
                            final var next = draft[0].deepCopy(); final var section = section(next);
                            final var bounds = new JsonArray(); bounds.add(drag.x / (double) getWidth()); bounds.add(drag.y / (double) getHeight());
                            bounds.add(drag.width / (double) getWidth()); bounds.add(drag.height / (double) getHeight());
                            if (selected.startsWith("region:")) { section.getAsJsonArray("regions").get(Integer.parseInt(selected.substring(7))).getAsJsonObject().add("bounds", bounds); }
                            else { widgets(section).add(selected, bounds); }
                            geometryDraft(next, original);
                            history.push(draft[0]); if (history.size() > 50) { history.removeLast(); } draft[0] = next;
                            status.setText("已验证：" + selected + " " + bounds);
                        } catch (RuntimeException ex) { status.setText("已撤回无效调整：" + ex.getMessage()); }
                        drag = null; start = null; repaint();
                    }
                }; addMouseListener(mouse); addMouseMotionListener(mouse);
            }
            private JsonObject section(JsonObject root) {
                final String id = (String) preset.getSelectedItem();
                if (id.equals("base")) { return root; }
                for (var v : root.getAsJsonObject("experience").getAsJsonArray("variants")) {
                    final var section = v.getAsJsonObject();
                    if (section.get("id").getAsString().equals(id)) {
                        if (!section.has("regions")) { section.add("regions", root.get("regions").deepCopy()); }
                        if (!section.has("widgets")) { section.add("widgets", root.getAsJsonObject("scene").get("widgets").deepCopy()); }
                        return section;
                    }
                } throw new IllegalArgumentException("Unknown variant");
            }
            private JsonObject widgets(JsonObject section) { return section.has("scene") ? section.getAsJsonObject("scene").getAsJsonObject("widgets") : section.getAsJsonObject("widgets"); }
            private Map<String, Rectangle> boxes() {
                final var section = section(draft[0]); final Map<String,Rectangle> result = new LinkedHashMap<>();
                widgets(section).entrySet().forEach(e -> result.put(e.getKey(), pixels(e.getValue())));
                final var regions = section.getAsJsonArray("regions");
                for (int i=0; i<regions.size(); i++) { result.put("region:" + i, pixels(regions.get(i).getAsJsonObject().get("bounds"))); }
                return result;
            }
            private Rectangle pixels(com.google.gson.JsonElement value) {
                final var b = MatchUiLayout.bounds(value);
                return new Rectangle((int)(b.x()*getWidth()), (int)(b.y()*getHeight()), (int)(b.w()*getWidth()), (int)(b.h()*getHeight()));
            }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g); g.setColor(new Color(0x243540));
                for (int x=0; x<getWidth(); x+=8) { g.drawLine(x,0,x,getHeight()); }
                for (int y=0; y<getHeight(); y+=8) { g.drawLine(0,y,getWidth(),y); }
                boxes().forEach((id,r) -> {
                    g.setColor(id.equals(selected) ? Color.ORANGE : id.startsWith("region:") ? new Color(0x7B9FB5) : new Color(0x66D9D0));
                    g.drawRect(r.x,r.y,r.width-1,r.height-1); g.fillRect(r.x+r.width-7,r.y+r.height-7,6,6);
                    g.drawString(id,r.x+3,r.y+14);
                });
                if (drag != null) { g.setColor(Color.RED); g.drawRect(drag.x,drag.y,drag.width,drag.height); }
            }
        }
        final var canvas = new Canvas(); final var bar = new JPanel(); bar.add(preset);
        preset.addActionListener(e -> canvas.repaint());
        final var undo = new JButton("撤销"); undo.addActionListener(e -> { if (!history.isEmpty()) { draft[0] = history.pop(); canvas.repaint(); } }); bar.add(undo);
        final var apply = new JButton("临时应用到本局"); apply.addActionListener(e -> {
            try { ui.preview(geometryDraft(draft[0], original)); status.setText("已临时应用。重新加载皮肤会恢复磁盘版本；请导出保存作品。"); }
            catch (RuntimeException ex) { status.setText(ex.getMessage()); }
        }); bar.add(apply);
        final var export = new JButton("导出 JSON 草稿…"); export.addActionListener(e -> {
            final var chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("match-ui-draft.json"));
            if (chooser.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                try { Files.writeString(chooser.getSelectedFile().toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(draft[0]), StandardOpenOption.CREATE_NEW);
                    status.setText("草稿已导出。配合原包素材，重命名为 match-ui.json 后使用资料包工具打包。"); }
                catch (java.io.IOException ex) { JOptionPane.showMessageDialog(dialog, "未保存（不会覆盖已有文件）：" + ex.getMessage()); }
            }
        }); bar.add(export);
        dialog.add(bar, BorderLayout.NORTH); dialog.add(canvas); dialog.add(status, BorderLayout.SOUTH);
        dialog.pack(); dialog.setLocationRelativeTo(null); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); dialog.setVisible(true);
    }
}
