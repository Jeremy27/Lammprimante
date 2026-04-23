package fr.courel.lammprimante;

import fr.courel.lammprimante.config.ThemeManager;
import fr.courel.lammprimante.service.LogService;
import fr.courel.lammprimante.service.UpdateService;
import fr.courel.lammprimante.view.MainWindow;

import javax.swing.*;

public class App {

    public static String getVersion() {
        try (var is = App.class.getResourceAsStream("/version.txt")) {
            return is != null ? new String(is.readAllBytes()).trim() : "inconnue";
        } catch (Exception e) {
            return "inconnue";
        }
    }

    public static void main(String[] args) {
        LogService.info("Démarrage de Lammprimante v" + getVersion());
        SwingUtilities.invokeLater(() -> {
            ThemeManager.applySaved();
            MainWindow window = new MainWindow();
            window.setVisible(true);
            UpdateService.checkForUpdatesAsync(window);
        });
    }
}
