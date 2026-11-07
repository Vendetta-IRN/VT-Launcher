package vt.launcher.core;

/** What the launcher screen shows while the game starts. Called from background threads. */
public interface LaunchListener {
    /** stage = big text, detail = small live line (last log line), fraction 0..1 (or -1 when unknown). */
    void progress(String stage, String detail, double fraction);

    /** The game window is open: the loading screen can go away. */
    default void windowOpened() {}

    /** The game process ended. windowWasOpen = false means it failed while loading. */
    default void exited(int code, boolean windowWasOpen) {}
}
