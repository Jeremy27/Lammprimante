package fr.jeremy.lammprimante;

import fr.jeremy.lammprimante.config.ThemeManager;
import fr.jeremy.lammprimante.service.LogService;
import fr.jeremy.lammprimante.ui.MainWindow;

import javax.swing.*;

public class App {

    public static void main(String[] args) {
        LogService.info("Démarrage de Lammprimante");
        SwingUtilities.invokeLater(() -> {
            ThemeManager.applySaved();
            new MainWindow().setVisible(true);
        });
    }
}
