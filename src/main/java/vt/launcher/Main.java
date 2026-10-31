package vt.launcher;

import javafx.application.Application;

/** Separate entry point (does not extend Application) so JavaFX works from the classpath. */
public final class Main {
    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
