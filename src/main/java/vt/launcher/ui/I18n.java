package vt.launcher.ui;

import java.util.Locale;
import java.util.ResourceBundle;

/** All UI text comes from vt/i18n/messages*.properties (English base, messages_fa = Persian). */
public final class I18n {
    private static ResourceBundle bundle = load("en");
    private static boolean rtl = false;

    private I18n() {}

    private static ResourceBundle load(String lang) {
        Locale l = "fa".equals(lang) ? Locale.forLanguageTag("fa") : Locale.ENGLISH;
        // no fallback to the PC's own language: only the chosen one, then English for missing texts
        return ResourceBundle.getBundle("vt.i18n.messages", l,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    public static void set(String lang) {
        bundle = load(lang);
        rtl = "fa".equals(lang);
    }

    public static boolean rtl() {
        return rtl;
    }

    public static String t(String key) {
        return bundle.containsKey(key) ? bundle.getString(key) : key;
    }
}
