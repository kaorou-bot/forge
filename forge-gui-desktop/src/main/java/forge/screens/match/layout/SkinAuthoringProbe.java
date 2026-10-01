package forge.screens.match.layout;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.toolbox.special.PhaseLabel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Offline diagnostics: no FModel, game loading, preferences, networking, or custom code from skins.
 * Uses production geometry/painters. Deliberately NOT a simulation of a complete match screen. */
public final class SkinAuthoringProbe {
    private SkinAuthoringProbe() { }
    public static JsonObject capabilities() throws Exception {
        try (var input = SkinAuthoringProbe.class.getResourceAsStream("/forge/skin-capabilities.json")) {
            if (input == null) { throw new IllegalStateException("Missing embedded skin capabilities"); }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static String json(Object value) { return new GsonBuilder().setPrettyPrinting().create().toJson(value); }
    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("capabilities")) {
                System.out.println(json(capabilities()));
                return;
            }
            if ((args.length != 5 && args.length != 6) || !args[0].equals("inspect")) {
                throw new IllegalArgumentException("Usage: capabilities | inspect <skin-directory> <new-output-directory> <content-width> <content-height>");
            }
            final var result = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
            SwingUtilities.invokeAndWait(() -> {
                try { result.set(inspect(Path.of(args[1]), Path.of(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]), args.length == 6 ? Integer.parseInt(args[5]) : 2)); }
                catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
            });
            System.out.println(json(result.get()));
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) { cause = cause.getCause(); }
            System.err.println(json(Map.of("ok", false, "error", String.valueOf(cause.getMessage()))));
            System.exit(1);
        }
    }
    private static Rectangle pixels(MatchUiLayout.Bounds b, int w, int h) {
        final int x = (int) Math.round(b.x() * w), y = (int) Math.round(b.y() * h);
        return new Rectangle(x, y, (int) Math.round((b.x() + b.w()) * w) - x, (int) Math.round((b.y() + b.h()) * h) - y);
    }
    private static List<Integer> box(Rectangle r) { return List.of(r.x, r.y, r.width, r.height); }
    private static String digest(Path path) throws Exception {
        final var hash = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            final byte[] buffer = new byte[65536]; int count;
            while ((count = stream.read(buffer)) != -1) { hash.update(buffer, 0, count); }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    static Map<String, Object> inspect(Path source, Path output, int width, int height) throws Exception {
        return inspect(source, output, width, height, 2);
    }
    static Map<String, Object> inspect(Path source, Path output, int width, int height, int players) throws Exception {
        if (players < 2 || players > 8) { throw new IllegalArgumentException("players must be 2..8"); }
        if (width < 320 || height < 240 || width > 7680 || height > 4320 || (long) width * height > 16_777_216) {
            throw new IllegalArgumentException("Invalid content viewport (320..7680 x 240..4320, at most 16M pixels)");
        }
        source = source.toRealPath(); output = output.toAbsolutePath().normalize();
        if (Files.exists(output) || output.startsWith(source)) { throw new IllegalArgumentException("Use a new output directory outside the skin"); }
        final Path config = source.resolve("match-ui.json");
        if (Files.size(config) > 1024 * 1024) { throw new IllegalArgumentException("Config exceeds 1 MiB"); }
        final MatchUiLayout base;
        try (var reader = Files.newBufferedReader(config)) { base = MatchUiLayout.read(reader, source); }
        final var choice = base.experience().choose(base,width,players,"AUTO");
        final MatchUiLayout layout = choice.layout();
        if (layout.scene() == null) { throw new IllegalArgumentException("Scene skin required"); }
        final var report = new LinkedHashMap<String, Object>();
        report.put("ok", true); report.put("capabilities", capabilities()); report.put("id", layout.id());
        report.put("variant",choice.id()); report.put("players",players);
        report.put("config_sha256", digest(config)); report.put("viewport", List.of(width, height));
        final var code = Path.of(SkinAuthoringProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        report.put("renderer_sha256", Files.isRegularFile(code) ? digest(code) : "development-classes");
        report.put("verification_level", "Production parser, image/font decoding, geometry and phase/visual primitives; NOT full UI or gameplay acceptance");
        report.put("limitations", List.of("Synthetic public sample cards only; no game logic, actual card stacking or pointer events",
                "Region used as card viewport; real borders/scrollbars can reduce it", "geometry.png is a diagnostic diagram, not a game screenshot",
                "components.png uses visual primitives; only phase chips use actual PhaseLabel controls. No fake prompt or avatar renderer."));
        final var docs = new ArrayList<>(List.of("FIELD_0", "FIELD_1", "HAND_0", "REPORT_STACK", "REPORT_MESSAGE", "BUTTON_DOCK",
                "CARD_PICTURE", "CARD_DETAIL", "REPORT_LOG", "REPORT_COMBAT", "REPORT_DEPENDENCIES"));
        for(int i=2;i<players;i++) { docs.add("FIELD_"+i); }
        final boolean supports = layout.scene().supports(docs);
        report.put("scene_support", supports ? "scene" : "arena_fallback");
        if(players==2) { report.put("two_player_one_hand",supports ? "scene" : "arena_fallback"); }
        final var placements = new ArrayList<Object>();
        final var geometry = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final var g = geometry.createGraphics();
        try {
            g.setColor(new Color(0x101C26)); g.fillRect(0, 0, width, height); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            if (supports) {
                for (var cell : layout.arrange(docs)) {
                    final var rect = pixels(cell.bounds(), width, height);
                    g.setColor(Color.GRAY); g.drawRect(rect.x, rect.y, rect.width - 1, rect.height - 1);
                    if (cell.documents().size() == 1) {
                        final String id = cell.documents().get(0);
                        final var cg = (Graphics2D) g.create(rect.x, rect.y, rect.width, rect.height);
                        try {
                            if (id.startsWith("HAND_") && layout.cards().hand() != null) {
                                final var cards = layout.cards().hand().arrange(7, rect.width, rect.height, 300);
                                placements.add(Map.of("id", id, "viewport", box(rect), "hand_cards", cards));
                                for (var p : cards) {
                                    final var card = (Graphics2D) cg.create();
                                    try {
                                        card.rotate(p.angle(), p.x() + p.width() / 2.0, p.y() + p.height() / 2.0);
                                        card.setColor(new Color(0x456777)); card.fillRect(p.x(), p.y(), p.width(), p.height());
                                        card.setColor(Color.WHITE); card.drawRect(p.x(), p.y(), p.width(), p.height());
                                    } finally { card.dispose(); }
                                }
                            } else if (id.startsWith("FIELD_") && layout.cards().battlefield() != null) {
                                final var strategy = layout.cards().battlefield();
                                final int cw = strategy.maximumCardWidth(rect.width, rect.height, 300, 50);
                                final var groups = new ArrayList<BattlefieldLayoutStrategy.Group>();
                                for (var kind : BattlefieldLayoutStrategy.Kind.values()) {
                                    final int count = kind == BattlefieldLayoutStrategy.Kind.LAND ? 8 : kind == BattlefieldLayoutStrategy.Kind.OTHER ? 4 : 6;
                                    for (int n = 0; n < count; n++) { groups.add(new BattlefieldLayoutStrategy.Group(cw, (int) Math.round(cw * 1.4), kind)); }
                                }
                                final var positions = strategy.placements(groups, rect.width, rect.height, !id.equals("FIELD_0"));
                                placements.add(Map.of("id", id, "viewport", box(rect), "sample_groups", groups, "placements", positions));
                                for (int n = 0; n < positions.size(); n++) {
                                    final var p = positions.get(n);
                                    cg.setColor(switch (groups.get(n).kind()) { case CREATURE -> new Color(0x508466); case LAND -> new Color(0x46668C); case OTHER -> new Color(0x855F94); });
                                    cg.fillRect(p.x(), p.y(), p.width(), p.height()); cg.setColor(Color.WHITE); cg.drawRect(p.x(), p.y(), p.width(), p.height());
                                }
                                cg.setColor(Color.ORANGE); cg.drawLine(rect.width / 2, 0, rect.width / 2, rect.height);
                            }
                        } finally { cg.dispose(); }
                    }
                    g.setColor(Color.WHITE); g.drawString(String.join(" / ", cell.documents()), rect.x + 3, rect.y + 15);
                }
            }
            layout.scene().widgets().forEach((id, b) -> {
                final var reserved = pixels(b,width,height);
                final var r = layout.scene().anchors().containsKey(id) ? layout.scene().anchors().get(id).fit(reserved) : reserved;
                g.setColor(Color.CYAN); g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
                g.drawString(id, r.x + 2, r.y + 14);
            });
            layout.scene().floating().forEach((id, spec) -> {
                final var r = pixels(spec.bounds(), width, height); g.setColor(Color.ORANGE); g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
                g.drawString("floating." + id, r.x + 2, r.y + 14);
            });
        } finally { g.dispose(); }
        report.put("geometry", placements);
        final var components = components(layout, width, height);
        Files.createDirectories(output.getParent()); Files.createDirectory(output);
        try (var stream = Files.newOutputStream(output.resolve("geometry.png"), StandardOpenOption.CREATE_NEW)) { ImageIO.write(geometry, "png", stream); }
        try (var stream = Files.newOutputStream(output.resolve("components.png"), StandardOpenOption.CREATE_NEW)) { ImageIO.write(components, "png", stream); }
        Files.writeString(output.resolve("capabilities.json"), json(capabilities()), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("report.json"), json(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return report;
    }
    private static BufferedImage components(MatchUiLayout layout, int width, int height) throws Exception {
        final var image = new BufferedImage(Math.max(1000, width), 800, BufferedImage.TYPE_INT_RGB);
        final var g = image.createGraphics();
        try {
            g.setColor(new Color(0x14222C)); g.fillRect(0, 0, image.getWidth(), image.getHeight()); g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            g.drawString("180x80 primitive swatches (NOT widget sizes): normal / hover / pressed / selected / disabled. NOT full UI controls.", 10, 20);
            final var theme = layout.scene().appearance();
            if (theme == null) { g.drawString("No appearance; visual preview skipped.", 10, 50); return image; }
            final var ids = List.of("PROMPT_OK", "FIELD_0.LIFE", "FIELD_0.ZONE_LIBRARY", "FIELD_0.AVATAR_IMAGE");
            for (int row = 0; row < ids.size(); row++) {
                g.setColor(Color.WHITE); g.drawString(ids.get(row), 10, 48 + row * 115);
                for (int state = 0; state < 5; state++) {
                    final var style = theme.style(ids.get(row)).state(state != 4, state == 1, state == 2, state == 3);
                    final var sg = (Graphics2D) g.create(10 + state * 192, 55 + row * 115, 180, 80);
                    try {
                        sg.setFont(theme.font(style.fontSize())); sg.setColor(theme.text());
                        style.paint(sg, 180, 80, state == 3, state == 2);
                        if (style.visual() != null) { style.visual().paintContent(sg, 180, 80, "样例 20", true); }
                        style.paintFrame(sg, 180, 80);
                    } finally { sg.dispose(); }
                }
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14)); g.setColor(Color.WHITE);
            g.drawString("Actual split PhaseLabel: upper active MAIN1, lower yield DRAW; dot=stop, line=active, arrow=yield.", 10, 540);
            final var bounds = pixels(layout.scene().widgets().get("PHASES_ACTIVE"), width, height);
            final int pad = theme.style("PHASES_ACTIVE").padding();
            final int chipWidth = Math.max(1, (bounds.width - pad * 2 - 42 - 33) / 12), chipHeight = Math.max(2, bounds.height - pad * 2);
            final var names = List.of("维持", "抓牌", "主阶1", "战斗始", "攻击", "阻挡", "先攻", "伤害", "战斗末", "主阶2", "结束", "清理");
            final var phases = capabilities().getAsJsonArray("phases");
            for (int i = 0; i < phases.size(); i++) {
                final var style = theme.style("PHASE." + phases.get(i).getAsString());
                if (style.visual() == null) { continue; } // Legacy palette requires a running client; never invent it.
                final var chip = new JPanel(new GridLayout(2, 1)); chip.setOpaque(false); chip.setSize(chipWidth, chipHeight);
                for (int half = 0; half < 2; half++) {
                    final var label = new PhaseLabel(names.get(i)); label.setFont(theme.font(style.fontSize())); label.setForeground(theme.text());
                    label.putClientProperty(MatchSkinTheme.STYLE_PROPERTY, style);
                    label.putClientProperty(PhaseLabel.SPLIT_HALF_PROPERTY, half == 0 ? PhaseLabel.SplitHalf.TOP : PhaseLabel.SplitHalf.BOTTOM);
                    label.setActive(half == 0 && i == 2); label.setYieldMarked(half == 1 && i == 1); chip.add(label);
                }
                chip.doLayout(); final var cg = (Graphics2D) g.create(10 + i * (chipWidth + 3), 560, chipWidth, Math.min(chipHeight, 230));
                try { chip.printAll(cg); } finally { cg.dispose(); }
            }
        } finally { g.dispose(); }
        return image;
    }
}
