package fr.courel.lammprimante.config;

import java.util.prefs.Preferences;

public class PreferencesManager {

    private static final Preferences PREFS = Preferences.userNodeForPackage(PreferencesManager.class);

    private static final String PREF_THEME = "theme";
    private static final String PREF_BATCH_SIZE = "batchSize";
    private static final String PREF_DUPLEX = "duplex";
    private static final String PREF_ORIENTATION = "orientation";
    private static final String PREF_COLOR = "color";

    // Thème
    public static String getTheme() {
        return PREFS.get(PREF_THEME, "Sombre");
    }

    public static void setTheme(String theme) {
        PREFS.put(PREF_THEME, theme);
    }

    // Pages par lot
    public static int getBatchSize() {
        return PREFS.getInt(PREF_BATCH_SIZE, 15);
    }

    public static void setBatchSize(int size) {
        PREFS.putInt(PREF_BATCH_SIZE, size);
    }

    // Recto/Verso (index de la combo : 0, 1, 2)
    public static int getDuplex() {
        return PREFS.getInt(PREF_DUPLEX, 0);
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
}
