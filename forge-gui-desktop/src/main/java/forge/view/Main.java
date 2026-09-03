/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.view;

import forge.GuiDesktop;
import forge.Singletons;
import forge.error.ExceptionHandler;
import forge.gui.GuiBase;
import forge.gui.card.CardReaderExperiments;

/**
 * Main class for Forge's swing application view.
 */
public final class Main {
    /**
     * Main entry point for Forge
     */
    public static void main(final String[] args) {
        // Command line modes must never touch the display: they run on servers, in CI, and over ssh.
        // This has to happen before any AWT class loads.
        final String mode = args.length > 0 ? args[0].toLowerCase() : "";
        if (isCommandLineMode(mode) && System.getProperty("java.awt.headless") == null) {
            // Only when the user hasn't decided: setting this here would otherwise beat an explicit
            // -Djava.awt.headless=false on the command line, since it runs before GraphicsEnvironment
            // caches its answer.
            System.setProperty("java.awt.headless", "true");
        }

        // Install a printing handler first so failures during early startup are visible rather than
        // producing a bare exit code.
        //
        // This cannot just be registerErrorHandling() moved up: that reads ForgeConstants.LOG_FILE,
        // whose class initializer resolves ASSETS_DIR through GuiBase.getInterface() and so needs the
        // GUI interface already set. The window we need covered includes setting it, which is where
        // the headless crash this fix is about used to happen — so the real handler cannot be
        // installed early enough to see it, and something simpler has to cover the gap.
        //
        // Scope: registerErrorHandling() then replaces the default handler outright, so this one
        // covers only the window up to that call. After it, visibility depends on BugReporter, which
        // prints before it reports.
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            System.err.println("Uncaught exception in thread " + t.getName() + ":");
            ex.printStackTrace();
            System.err.flush();
        });

        // HACK - temporary solution to "Comparison method violates it's general contract!" crash
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");

        //Turn off the Java 2D system's use of Direct3D to improve rendering speed (particularly when Full Screen)
        System.setProperty("sun.java2d.d3d", "false");

        //Turn on OpenGl acceleration to improve performance
        //System.setProperty("sun.java2d.opengl", "True");

        //setup GUI interface
        GuiBase.setInterface(new GuiDesktop());

        //install our error handler
        ExceptionHandler.registerErrorHandling();
        GuiBase.logHWInfo();

        // Start splash screen first, then data models, then controller.
        if (args.length == 0) {
            Singletons.initializeOnce(true);

            // Controller can now step in and take over.
            Singletons.getControl().initialize();
            return;
        }

        // command line startup here
        switch (mode) {
            case "sim":
                SimulateMatch.simulate(args);
                break;

            case "parse":
                CardReaderExperiments.parseAllCards(args);
                break;

            case "server":
                System.out.println("Dedicated server mode.\nNot implemented.");
                break;

            default:
                System.out.println("Unknown mode.\nKnown modes are 'sim', 'parse', 'server'");
                break;
        }

        System.exit(0);
    }

    /**
     * Modes that run to completion on the console and never open a window, and so should force
     * headless AWT. Keep in sync with the named cases of the switch in {@link #main(String[])}.
     * The switch's {@code default} arm also stays on the console, but an unrecognised argument is
     * not a mode and only prints usage, so it is deliberately not listed here.
     */
    public static boolean isCommandLineMode(final String mode) {
        return "sim".equals(mode) || "parse".equals(mode) || "server".equals(mode);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void finalize() throws Throwable {
        try {
            ExceptionHandler.unregisterErrorHandling();
        } finally {
            super.finalize();
        }
    }

    // disallow instantiation
    private Main() {
    }
}
