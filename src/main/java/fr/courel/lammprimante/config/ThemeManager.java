package fr.courel.lammprimante.config;

import fr.courel.lammui.theme.LammTheme;

/**
 * Petite façade au-dessus de LammTheme pour persister le choix light/dark.
 */
public final class ThemeManager {

    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    private ThemeManager() {}

    public static void applySaved() {
        LammTheme.setTheme(isDarkSaved() ? new LammTheme.Dark() : new LammTheme.Light());
    }

    public static boolean isDarkSaved() {
        return DARK.equalsIgnoreCase(PreferencesManager.getTheme());
    }

    public static void save(boolean dark) {
        PreferencesManager.setTheme(dark ? DARK : LIGHT);
    }
}
