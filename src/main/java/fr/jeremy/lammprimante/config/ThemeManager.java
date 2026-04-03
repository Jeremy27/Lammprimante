package fr.jeremy.lammprimante.config;

import mdlaf.MaterialLookAndFeel;
import mdlaf.themes.*;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

public class ThemeManager {

    private static final Preferences PREFS = Preferences.userNodeForPackage(ThemeManager.class);
    private static final String PREF_THEME = "theme";
    private static final String DEFAULT_THEME = "Sombre";

    private static final Map<String, MaterialTheme> THEMES = new LinkedHashMap<>();
    static {
        THEMES.put("Sombre", new MaterialOceanicTheme());
        THEMES.put("Clair", new MaterialLiteTheme());
        THEMES.put("Sombre contrasté", new JMarsDarkTheme());
    }

    public static Set<String> getThemeNames() {
        return THEMES.keySet();
    }

    public static String getSavedTheme() {
        return PREFS.get(PREF_THEME, DEFAULT_THEME);
    }

    public static void applySaved() {
        String savedTheme = getSavedTheme();
        MaterialTheme theme = THEMES.getOrDefault(savedTheme, THEMES.get(DEFAULT_THEME));
        try {
            UIManager.setLookAndFeel(new MaterialLookAndFeel(theme));
        } catch (Exception ignored) {}
    }

    public static void apply(String themeName, Component root) {
        MaterialTheme theme = THEMES.get(themeName);
        if (theme == null) return;
        try {
            UIManager.setLookAndFeel(new MaterialLookAndFeel(theme));
            SwingUtilities.updateComponentTreeUI(root);
            PREFS.put(PREF_THEME, themeName);
        } catch (UnsupportedLookAndFeelException ex) {
            JOptionPane.showMessageDialog(root,
                    "Impossible d'appliquer le thème : " + ex.getMessage(),
                    "Erreur", JOptionPane.ERROR_MESSAGE);
        }
    }
}
