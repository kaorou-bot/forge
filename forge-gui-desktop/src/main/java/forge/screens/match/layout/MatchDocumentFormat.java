package forge.screens.match.layout;

import com.google.gson.JsonObject;
import java.awt.Insets;
import java.util.Set;
import javax.swing.text.JTextComponent;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.StyleSheet;

/** Formatting for document text, independent from label artwork and panel opacity. */
public record MatchDocumentFormat(int margin, int paragraphGap, String align) {
    static MatchDocumentFormat read(JsonObject json) {
        MatchUiLayout.keys(json, Set.of("margin", "paragraphGap", "align"));
        final String align = json.has("align") ? json.get("align").getAsString() : "LEFT";
        if (!Set.of("LEFT", "CENTER", "RIGHT").contains(align)) { throw new IllegalArgumentException("Invalid document alignment"); }
        return new MatchDocumentFormat((int) MatchSkinTheme.optionalNumber(json,"margin",6,0,32,true),
                (int) MatchSkinTheme.optionalNumber(json,"paragraphGap",4,0,32,true), align);
    }
    Runnable apply(JTextComponent text) {
        final Insets old = text.getMargin(); text.setMargin(new Insets(margin, margin, margin, margin));
        final var sheets = new java.util.IdentityHashMap<HTMLDocument,StyleSheet>();
        final Runnable style = () -> {
            if (text.getDocument() instanceof HTMLDocument document && !sheets.containsKey(document)) {
                final var sheet = new StyleSheet();
                sheet.addRule("body { margin: 0; text-align: " + align.toLowerCase(java.util.Locale.ROOT) + "; }");
                sheet.addRule("p { margin-top: 0; margin-bottom: " + paragraphGap + "px; text-align: " + align.toLowerCase(java.util.Locale.ROOT) + "; }");
                document.getStyleSheet().addStyleSheet(sheet); sheets.put(document, sheet);
            }
        };
        final java.beans.PropertyChangeListener listener = e -> { if (e.getPropertyName().equals("document")) { style.run(); } };
        text.addPropertyChangeListener(listener); style.run();
        return () -> { text.removePropertyChangeListener(listener); text.setMargin(old); sheets.forEach((d,s) -> d.getStyleSheet().removeStyleSheet(s)); };
    }
}
