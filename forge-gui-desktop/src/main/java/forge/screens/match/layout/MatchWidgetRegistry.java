package forge.screens.match.layout;

import forge.game.zone.ZoneType;
import forge.screens.match.CMatchUI;
import forge.screens.match.ZoneAction;
import forge.screens.match.views.VDock.DockButtonId;
import forge.screens.match.views.VField;
import forge.toolbox.FLabel;
import forge.util.Localizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/** Trusted renderer extension point. Factories operate on the EDT and must preserve visibility rules. */
public final class MatchWidgetRegistry {
    public record Context(CMatchUI match, VField field, String type, MatchSkinTheme theme) {
        public Context(CMatchUI match, VField field, String type) { this(match, field, type, null); }
    }
    public record Widget(JComponent component, Runnable refresh, Runnable dispose) {
        public Widget(JComponent component) { this(component, component::repaint, () -> { }); }
    }
    private static final Map<String, Function<Context, Widget>> FACTORIES = new LinkedHashMap<>();
    static {
        register("PROMPT_MESSAGE", c -> new Widget(c.match().getCPrompt().getView().getMessageScroller()));
        register("PROMPT_OK", c -> new Widget(c.match().getCPrompt().getView().getBtnOK()));
        register("PROMPT_CANCEL", c -> new Widget(c.match().getCPrompt().getView().getBtnCancel()));
        register("PROMPT_CONTEXT", c -> new Widget(c.match().getCPrompt().getView().getLblGames()));
        register("AVATAR", c -> new Widget(c.field().getAvatarArea()));
        register("AVATAR_IMAGE", c -> new Widget(c.field().getAvatarImageComponent()));
        register("LIFE", c -> new Widget(c.field().getLifeComponent()));
        register("NAME", c -> text(() -> c.field().getPlayer().getName()));
        register("STATUS", c -> text(() -> {
            final var counters = c.field().getPlayer().getCounters();
            if (counters == null) { return ""; }
            return counters.entrySet().stream().map(e -> e.getElement().getName() + " " + e.getCount())
                    .collect(java.util.stream.Collectors.joining(" · "));
        }));
        register("DETAILS", c -> new Widget(c.field().getDetailsPanel()));
        register("MANA", c -> new Widget(c.field().getDetailsPanel().createManaRow()));
        for (String color : new String[]{"W", "U", "B", "R", "G", "C"}) {
            register("MANA_" + color, c -> new Widget(c.field().getDetailsPanel().getManaComponent(color)));
        }
        register("ZONES", c -> new Widget(new SceneZoneView(c.match(), c.field().getPlayer(), false)));
        register("HAND_BACKS", c -> new Widget(new SceneZoneView(c.match(), c.field().getPlayer(), true)));
        for (ZoneType zone : new ZoneType[]{ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand,
                ZoneType.Flashback, ZoneType.Command, ZoneType.Sideboard}) {
            register("ZONE_" + zone.name().toUpperCase(java.util.Locale.ROOT), c -> zone(c, true));
        }
        register("ZONE_PILE", c -> zone(c, true));
        register("ZONE_BUTTON", c -> zone(c, false));
        register("OTHER_ZONES", c -> {
            final FLabel button = button(Localizer.getInstance().getMessage("lblDesktopMatchUiOtherZones"));
            button.setCommand((Runnable) () -> {
                final JPopupMenu menu = new JPopupMenu();
                final var zones = java.util.EnumSet.copyOf(CMatchUI.FLOATING_ZONE_TYPES);
                zones.add(ZoneType.Hand);
                for (ZoneType zone : zones) {
                    if (zone == ZoneType.Library) { continue; }
                    final JMenuItem item = new JMenuItem(zone.getTranslatedName() + " · " + c.field().getPlayer().getZoneSize(zone));
                    item.addActionListener(e -> new ZoneAction(c.match(), c.field().getPlayer(), zone).run());
                    menu.add(item);
                }
                menu.show(button, 0, button.getHeight());
            });
            return new Widget(button);
        });
        register("STACK_STATUS", c -> text(() -> {
            final var game = c.match().getGameView();
            final int count = game == null ? 0 : game.getStack().size();
            return Localizer.getInstance().getMessage("lblStack") + " · " + count
                    + (count == 0 ? " — " + Localizer.getInstance().getMessage("lblDesktopMatchUiStackEmpty") : "");
        }));
        for (DockButtonId id : DockButtonId.values()) {
            register("ACTION_" + id.name(), c -> {
                final var dock = c.match().getCDock().getView();
                final FLabel button = button(dock.getActionLabel(id));
                button.setCommand((Runnable) () -> dock.performAction(id));
                return new Widget(button, () -> {
                    button.setEnabled(dock.getButton(id).isEnabled());
                    button.setToolTipText(dock.getButton(id).getToolTipText());
                }, () -> { });
            });
        }
        register("ACTIONS_MENU", c -> new MatchActionsWindow(c).widget());
    }

    private MatchWidgetRegistry() { }

    /** Register or replace a renderer before loading the scene. JSON names renderers, never classes/scripts. */
    public static void register(String id, Function<Context, Widget> factory) {
        if (id == null || !id.matches("[A-Z][A-Z0-9_]*") || factory == null) {
            throw new IllegalArgumentException("Invalid match widget renderer");
        }
        FACTORIES.put(id, factory);
    }
    public static boolean contains(String id) { return FACTORIES.containsKey(id); }
    public static Widget create(String renderer, Context context) {
        final var factory = FACTORIES.get(renderer);
        if (factory == null) { throw new IllegalArgumentException("Unknown renderer: " + renderer); }
        return factory.apply(context);
    }
    private static Widget zone(Context c, boolean pile) {
        final String name = c.type().substring("ZONE_".length());
        final ZoneType zone = java.util.Arrays.stream(ZoneType.values()).filter(z -> z.name().equalsIgnoreCase(name))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Zone renderer requires a ZONE_* widget"));
        return new Widget(new SceneZoneTile(c.match(), c.field().getPlayer(), zone, pile));
    }
    private static FLabel button(String text) { return new FLabel.ButtonBuilder().text(text).fontSize(13).build(); }
    private static Widget text(Supplier<String> value) {
        final FLabel label = new FLabel.Builder().fontSize(15).build();
        return new Widget(label, () -> label.setText(value.get()), () -> { });
    }
}
