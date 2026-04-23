package fr.courel.lammprimante.config;

import java.util.prefs.Preferences;

public class PreferencesManager {

    private static final Preferences PREFS = Preferences.userNodeForPackage(PreferencesManager.class);

    private static final String PREF_THEME = "theme";
    private static final String PREF_BATCH_SIZE = "batchSize";
    private static final String PREF_DUPLEX = "duplex";
    private static final String PREF_ORIENTATION = "orientation";
    private static final String PREF_COLOR = "color";

    // Thème : "light" ou "dark"
    public static String getTheme() {
        String raw = PREFS.get(PREF_THEME, "dark");
        // Migration des anciennes valeurs (mdlaf) vers light/dark
        return switch (raw.toLowerCase()) {
            case "clair", "light" -> "light";
            default -> "dark";
        };
    }

    public static void setTheme(String theme) {
        PREFS.put(PREF_THEME, theme);
    }

    // Pages par lot
    public static int getBatchSize() {
        return PREFS.getInt(PREF_BATCH_SIZE, 20);
    }

    public static void setBatchSize(int size) {
        PREFS.putInt(PREF_BATCH_SIZE, size);
    }

    // Recto/Verso (index de la combo : 0 = recto seul, 1 = livre, 2 = bloc-notes)
    public static int getDuplex() {
        return PREFS.getInt(PREF_DUPLEX, 1);
    }

    public static void setDuplex(int index) {
        PREFS.putInt(PREF_DUPLEX, index);
    }

    // Orientation (index : 0 = portrait, 1 = paysage)
    public static int getOrientation() {
        return PREFS.getInt(PREF_ORIENTATION, 0);
    }

    public static void setOrientation(int index) {
        PREFS.putInt(PREF_ORIENTATION, index);
    }

    // Couleur (index : 0 = couleur, 1 = N&B)
    public static int getColor() {
        return PREFS.getInt(PREF_COLOR, 0);
    }

    public static void setColor(int index) {
        PREFS.putInt(PREF_COLOR, index);
    }

    // Fenêtre
    public static int getWindowX() { return PREFS.getInt("windowX", -1); }
    public static int getWindowY() { return PREFS.getInt("windowY", -1); }
    public static int getWindowWidth() { return PREFS.getInt("windowWidth", 750); }
    public static int getWindowHeight() { return PREFS.getInt("windowHeight", 600); }
    public static boolean getWindowMaximized() { return PREFS.getBoolean("windowMaximized", false); }

    public static void setWindowBounds(int x, int y, int width, int height) {
        PREFS.putInt("windowX", x);
        PREFS.putInt("windowY", y);
        PREFS.putInt("windowWidth", width);
        PREFS.putInt("windowHeight", height);
    }

    public static void setWindowMaximized(boolean maximized) {
        PREFS.putBoolean("windowMaximized", maximized);
    }
}
