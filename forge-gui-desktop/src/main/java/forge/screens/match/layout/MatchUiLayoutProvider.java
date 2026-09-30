package forge.screens.match.layout;

import java.io.IOException;

/** Register a desktop provider before building the Layout menu. Never change game state here. */
@FunctionalInterface
public interface MatchUiLayoutProvider {
    MatchUiLayout load() throws IOException;
}
