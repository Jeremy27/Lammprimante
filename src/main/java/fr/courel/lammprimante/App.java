package fr.courel.lammprimante;

import fr.courel.lammprimante.config.ThemeManager;
import fr.courel.lammprimante.service.LogService;
import fr.courel.lammprimante.view.MainWindow;

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
