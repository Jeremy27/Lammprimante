package fr.courel.lammprimante.view;

import fr.courel.lammprimante.config.PreferencesManager;
import fr.courel.lammprimante.config.ThemeManager;
import fr.courel.lammprimante.model.PrintSettings;
import fr.courel.lammprimante.service.FileImportService;
import fr.courel.lammprimante.service.LogService;
import fr.courel.lammprimante.service.PrintService;

import javax.imageio.ImageIO;
import javax.print.PrintServiceLookup;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainWindow extends JFrame {

    private final FileImportService fileImportService = new FileImportService();

    private final DefaultListModel<File> fileListModel = new DefaultListModel<>();
    private final JList<File> fileList = new JList<>(fileListModel);
    private final JComboBox<javax.print.PrintService> printerCombo;
    private final JSpinner batchSizeSpinner = new JSpinner(new SpinnerNumberModel(15, 1, 100, 1));
    private final JSpinner copiesSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 99, 1));
    private final JComboBox<String> rectoVersoCombo = new JComboBox<>(new String[]{
            "Recto seul", "Recto/Verso (bord long)", "Recto/Verso (bord court)"});
    private final JComboBox<String> pagesPerSheetCombo = new JComboBox<>(new String[]{"1", "2", "4"});
    private final JComboBox<String> orientationCombo = new JComboBox<>(new String[]{"Portrait", "Paysage"});
    private final JComboBox<String> colorCombo = new JComboBox<>(new String[]{"Couleur", "Noir et blanc"});
    private final JTextArea logArea = new JTextArea();
    private final JButton printButton = new JButton("Imprimer");
    private final JButton addButton = new JButton("Ajouter des fichiers...");
    private final JButton removeButton = new JButton("Retirer");
    private final JProgressBar progressBar = new JProgressBar();

    public MainWindow() {
        super("Lammprimante");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(750, 600);
        setMinimumSize(new Dimension(600, 500));
        setLocationRelativeTo(null);
        loadIcon();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                fileImportService.cleanup();
            }
        });

        printerCombo = createPrinterCombo();

        // Restaurer les préférences sauvegardées
        batchSizeSpinner.setValue(PreferencesManager.getBatchSize());
        rectoVersoCombo.setSelectedIndex(PreferencesManager.getDuplex());
        orientationCombo.setSelectedIndex(PreferencesManager.getOrientation());
        colorCombo.setSelectedIndex(PreferencesManager.getColor());

        // Sauvegarder à chaque modification
        batchSizeSpinner.addChangeListener(e -> PreferencesManager.setBatchSize((int) batchSizeSpinner.getValue()));
        rectoVersoCombo.addActionListener(e -> PreferencesManager.setDuplex(rectoVersoCombo.getSelectedIndex()));
        orientationCombo.addActionListener(e -> PreferencesManager.setOrientation(orientationCombo.getSelectedIndex()));
        colorCombo.addActionListener(e -> PreferencesManager.setColor(colorCombo.getSelectedIndex()));

        fileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof File f) {
                    setText(fileImportService.getDisplayName(f));
                    setToolTipText(f.getAbsolutePath());
                }
                return this;
            }
        });

        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(createFilePanel(), BorderLayout.CENTER);
        add(createSettingsPanel(), BorderLayout.NORTH);
        add(createBottomPanel(), BorderLayout.SOUTH);

        addButton.addActionListener(e -> addFiles());
        removeButton.addActionListener(e -> removeSelected());
        printButton.addActionListener(e -> startPrinting());
    }

    private void loadIcon() {
        try {
            Image source = ImageIO.read(getClass().getResourceAsStream("/logo.jpg"));
            int size = 64;
            java.awt.image.BufferedImage icon = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = icon.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setClip(new java.awt.geom.Ellipse2D.Float(0, 0, size, size));
            int srcW = source.getWidth(null);
            int srcH = source.getHeight(null);
            int cropSize = Math.min(srcW, srcH);
            int sx = (srcW - cropSize) / 2;
            int sy = (srcH - cropSize) / 2;
            g2.drawImage(source, 0, 0, size, size, sx, sy, sx + cropSize, sy + cropSize, null);
            g2.dispose();
            setIconImage(icon);
        } catch (Exception ignored) {}
    }

    private JComboBox<javax.print.PrintService> createPrinterCombo() {
        javax.print.PrintService[] printers = PrintServiceLookup.lookupPrintServices(null, null);
        JComboBox<javax.print.PrintService> combo = new JComboBox<>(printers);
        javax.print.PrintService defaultPrinter = PrintServiceLookup.lookupDefaultPrintService();
        if (defaultPrinter != null) {
            combo.setSelectedItem(defaultPrinter);
        }
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof javax.print.PrintService ps) {
                    setText(ps.getName());
                }
                return this;
            }
        });
        return combo;
    }

    private JPanel createFilePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Fichiers (glisser-déposer PDF, images, ZIP ou dossiers ici)"));
        panel.add(new JScrollPane(fileList), BorderLayout.CENTER);

        new DropTarget(fileList, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> droppedFiles = (List<File>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    importFiles(droppedFiles);
                    e.dropComplete(true);
                } catch (Exception ex) {
                    e.rejectDrop();
                }
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(addButton);
        buttons.add(removeButton);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createSettingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Paramètres"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Ligne 1
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Imprimante :"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(printerCombo, gbc);
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;

        gbc.gridx = 4;
        panel.add(new JLabel("Thème :"), gbc);
        gbc.gridx = 5;
        JComboBox<String> themeCombo = new JComboBox<>(ThemeManager.getThemeNames().toArray(new String[0]));
        themeCombo.setSelectedItem(ThemeManager.getSavedTheme());
        themeCombo.addActionListener(e -> ThemeManager.apply((String) themeCombo.getSelectedItem(), this));
        panel.add(themeCombo, gbc);

        // Ligne 2
        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Recto/Verso :"), gbc);
        gbc.gridx = 1;
        panel.add(rectoVersoCombo, gbc);
        gbc.gridx = 2;
        panel.add(new JLabel("Orientation :"), gbc);
        gbc.gridx = 3;
        panel.add(orientationCombo, gbc);
        gbc.gridx = 4;
        panel.add(new JLabel("Couleur :"), gbc);
        gbc.gridx = 5;
        panel.add(colorCombo, gbc);

        // Ligne 3
        gbc.gridx = 0; gbc.gridy = 2;
        panel.add(new JLabel("Pages par lot :"), gbc);
        gbc.gridx = 1;
        panel.add(batchSizeSpinner, gbc);
        gbc.gridx = 2;
        panel.add(new JLabel("Pages par feuille :"), gbc);
        gbc.gridx = 3;
        panel.add(pagesPerSheetCombo, gbc);
        gbc.gridx = 4;
        panel.add(new JLabel("Copies :"), gbc);
        gbc.gridx = 5;
        panel.add(copiesSpinner, gbc);

        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        logArea.setEditable(false);
        logArea.setRows(6);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Journal"));
        panel.add(logScroll, BorderLayout.CENTER);

        JPanel actionPanel = new JPanel(new BorderLayout(5, 0));
        progressBar.setStringPainted(true);
        progressBar.setString("");
        actionPanel.add(progressBar, BorderLayout.CENTER);
        actionPanel.add(printButton, BorderLayout.EAST);
        panel.add(actionPanel, BorderLayout.SOUTH);

        return panel;
    }

    private PrintSettings buildPrintSettings() {
        PrintSettings.DuplexMode duplex = switch (rectoVersoCombo.getSelectedIndex()) {
            case 1 -> PrintSettings.DuplexMode.LONG_EDGE;
            case 2 -> PrintSettings.DuplexMode.SHORT_EDGE;
            default -> PrintSettings.DuplexMode.RECTO;
        };

        return new PrintSettings(
                (int) batchSizeSpinner.getValue(),
                (int) copiesSpinner.getValue(),
                duplex,
                orientationCombo.getSelectedIndex() == 1 ? PrintSettings.Orientation.LANDSCAPE : PrintSettings.Orientation.PORTRAIT,
                colorCombo.getSelectedIndex() == 1 ? PrintSettings.ColorMode.MONOCHROME : PrintSettings.ColorMode.COLOR,
                Integer.parseInt((String) pagesPerSheetCombo.getSelectedItem())
        );
    }

    private void importFiles(List<File> files) {
        LogService.info("Import de " + files.size() + " fichier(s)");
        logArea.append("Import de " + files.size() + " fichier(s)...\n");
        setControlsEnabled(false);

        SwingWorker<FileImportService.ImportResult, String> worker = new SwingWorker<>() {
            @Override
            protected FileImportService.ImportResult doInBackground() {
                return fileImportService.importFiles(files, displayName -> publish("  + " + displayName));
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String msg : chunks) {
                    logArea.append(msg + "\n");
                    LogService.info("Fichier ajouté :" + msg.substring(3));
                }
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }

            @Override
            protected void done() {
                try {
                    FileImportService.ImportResult result = get();

                    for (File f : result.accepted()) {
                        if (!fileListModel.contains(f)) {
                            fileListModel.addElement(f);
                        }
                    }

                    if (!result.rejected().isEmpty()) {
                        logArea.append("Fichiers ignorés (format non supporté) :\n");
                        for (String name : result.rejected()) {
                            logArea.append("  - " + name + "\n");
                            LogService.info("Fichier ignoré : " + name);
                        }
                    }

                    if (!result.errors().isEmpty()) {
                        for (String error : result.errors()) {
                            logArea.append("ERREUR : " + error + "\n");
                            LogService.error(error);
                        }
                    }

                    logArea.append("Import terminé : " + result.accepted().size() + " fichier(s) ajouté(s)\n");
                    LogService.info("Import terminé : " + result.accepted().size() + " fichier(s)");
                } catch (Exception ex) {
                    logArea.append("ERREUR : " + ex.getMessage() + "\n");
                    LogService.error("Erreur lors de l'import", ex);
                }
                logArea.setCaretPosition(logArea.getDocument().getLength());
                setControlsEnabled(true);
            }
        };
        worker.execute();
    }

    private void addFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setFileFilter(new FileNameExtensionFilter("PDF, images et ZIP", "pdf", "zip", "jpg", "jpeg", "png", "bmp", "gif", "tiff", "tif"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            importFiles(List.of(chooser.getSelectedFiles()));
        }
    }

    private void removeSelected() {
        int[] indices = fileList.getSelectedIndices();
        for (int i = indices.length - 1; i >= 0; i--) {
            fileListModel.remove(indices[i]);
        }
    }

    private void startPrinting() {
        if (fileListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Aucun fichier sélectionné.", "Attention", JOptionPane.WARNING_MESSAGE);
            return;
        }
        javax.print.PrintService printer = (javax.print.PrintService) printerCombo.getSelectedItem();
        if (printer == null) {
            JOptionPane.showMessageDialog(this, "Aucune imprimante disponible.", "Erreur", JOptionPane.ERROR_MESSAGE);
            return;
        }
        PrintSettings settings = buildPrintSettings();

        List<File> files = new ArrayList<>();
        for (int i = 0; i < fileListModel.size(); i++) {
            files.add(fileListModel.get(i));
        }

        setControlsEnabled(false);
        logArea.setText("");
        progressBar.setValue(0);
        progressBar.setMaximum(files.size());

        LogService.info("Lancement impression : " + files.size() + " fichier(s), imprimante=" + printer.getName());

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                PrintService service = new PrintService();
                for (int i = 0; i < files.size(); i++) {
                    File file = files.get(i);
                    try {
                        service.print(file, printer, settings, progress -> {
                            String msg = String.format("[%s] Lot %d/%d (pages %d à %d) envoyé",
                                    progress.fileName(), progress.batchNumber(), progress.totalBatches(),
                                    progress.fromPage(), progress.toPage());
                            publish(msg);
                            LogService.info(msg);
                        });
                        int current = i + 1;
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setValue(current);
                            progressBar.setString(current + " / " + files.size());
                        });
                    } catch (Exception ex) {
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        publish("ERREUR [" + file.getName() + "] : " + msg);
                        LogService.error("Impression échouée pour " + file.getName(), ex);
                    }
                }
                return null;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String msg : chunks) {
                    logArea.append(msg + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            }

            @Override
            protected void done() {
                setControlsEnabled(true);
                logArea.append("--- Terminé ---\n");
                if (logArea.getText().contains("ERREUR")) {
                    logArea.append("Détails des erreurs dans : " + LogService.getLogFile() + "\n");
                }
                logArea.setCaretPosition(logArea.getDocument().getLength());
                progressBar.setString("Terminé");
                LogService.info("Impression terminée");
            }
        };
        worker.execute();
    }

    private void setControlsEnabled(boolean enabled) {
        printButton.setEnabled(enabled);
        addButton.setEnabled(enabled);
        removeButton.setEnabled(enabled);
        printerCombo.setEnabled(enabled);
        batchSizeSpinner.setEnabled(enabled);
        copiesSpinner.setEnabled(enabled);
        rectoVersoCombo.setEnabled(enabled);
        pagesPerSheetCombo.setEnabled(enabled);
        orientationCombo.setEnabled(enabled);
        colorCombo.setEnabled(enabled);
    }
}
