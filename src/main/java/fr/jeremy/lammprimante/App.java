package fr.jeremy.lammprimante;

import mdlaf.MaterialLookAndFeel;
import mdlaf.themes.*;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.Chromaticity;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.Sides;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.print.PrinterJob;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class App extends JFrame {

    private static final Preferences PREFS = Preferences.userNodeForPackage(App.class);
    private static final String PREF_THEME = "theme";
    private static final String DEFAULT_THEME = "Sombre";

    private static final Map<String, MaterialTheme> THEMES = new LinkedHashMap<>();
    static {
        THEMES.put("Sombre", new MaterialOceanicTheme());
        THEMES.put("Clair", new MaterialLiteTheme());
        THEMES.put("Sombre contrasté", new JMarsDarkTheme());
    }

    private final DefaultListModel<File> fileListModel = new DefaultListModel<>();
    private final JList<File> fileList = new JList<>(fileListModel);
    private final JComboBox<PrintService> printerCombo;
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

    public App() {
        super("Lammprimante");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(750, 600);
        setMinimumSize(new Dimension(600, 500));
        setLocationRelativeTo(null);

        try {
            Image icon = ImageIO.read(getClass().getResourceAsStream("/logo.jpg"));
            setIconImage(createRoundIcon(icon, 64));
        } catch (Exception ignored) {}

        PrintService[] printers = PrintServiceLookup.lookupPrintServices(null, null);
        printerCombo = new JComboBox<>(printers);
        PrintService defaultPrinter = PrintServiceLookup.lookupDefaultPrintService();
        if (defaultPrinter != null) {
            printerCombo.setSelectedItem(defaultPrinter);
        }
        printerCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PrintService ps) {
                    setText(ps.getName());
                }
                return this;
            }
        });

        fileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof File f) {
                    setText(f.getName());
                    setToolTipText(f.getAbsolutePath());
                }
                return this;
            }
        });

        setLayout(new BorderLayout(10, 10));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // --- Top: file list + buttons ---
        JPanel filePanel = new JPanel(new BorderLayout(5, 5));
        filePanel.setBorder(BorderFactory.createTitledBorder("Fichiers (glisser-déposer PDF, images ou ZIP ici)"));

        JScrollPane fileScroll = new JScrollPane(fileList);
        filePanel.add(fileScroll, BorderLayout.CENTER);

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

        JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fileButtons.add(addButton);
        fileButtons.add(removeButton);
        filePanel.add(fileButtons, BorderLayout.SOUTH);

        // --- Middle: settings ---
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        settingsPanel.setBorder(BorderFactory.createTitledBorder("Paramètres"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Ligne 1
        gbc.gridx = 0; gbc.gridy = 0;
        settingsPanel.add(new JLabel("Imprimante :"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL;
        settingsPanel.add(printerCombo, gbc);
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;

        gbc.gridx = 4;
        settingsPanel.add(new JLabel("Thème :"), gbc);
        gbc.gridx = 5;
        JComboBox<String> themeCombo = new JComboBox<>(THEMES.keySet().toArray(new String[0]));
        themeCombo.setSelectedItem(PREFS.get(PREF_THEME, DEFAULT_THEME));
        themeCombo.addActionListener(e -> applyTheme((String) themeCombo.getSelectedItem()));
        settingsPanel.add(themeCombo, gbc);

        // Ligne 2
        gbc.gridx = 0; gbc.gridy = 1;
        settingsPanel.add(new JLabel("Recto/Verso :"), gbc);
        gbc.gridx = 1;
        settingsPanel.add(rectoVersoCombo, gbc);

        gbc.gridx = 2;
        settingsPanel.add(new JLabel("Orientation :"), gbc);
        gbc.gridx = 3;
        settingsPanel.add(orientationCombo, gbc);

        gbc.gridx = 4;
        settingsPanel.add(new JLabel("Couleur :"), gbc);
        gbc.gridx = 5;
        settingsPanel.add(colorCombo, gbc);

        // Ligne 3
        gbc.gridx = 0; gbc.gridy = 2;
        settingsPanel.add(new JLabel("Pages par lot :"), gbc);
        gbc.gridx = 1;
        settingsPanel.add(batchSizeSpinner, gbc);

        gbc.gridx = 2;
        settingsPanel.add(new JLabel("Pages par feuille :"), gbc);
        gbc.gridx = 3;
        settingsPanel.add(pagesPerSheetCombo, gbc);

        gbc.gridx = 4;
        settingsPanel.add(new JLabel("Copies :"), gbc);
        gbc.gridx = 5;
        settingsPanel.add(copiesSpinner, gbc);

        // --- Bottom: log + print ---
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        logArea.setEditable(false);
        logArea.setRows(6);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Journal"));
        bottomPanel.add(logScroll, BorderLayout.CENTER);

        JPanel actionPanel = new JPanel(new BorderLayout(5, 0));
        progressBar.setStringPainted(true);
        progressBar.setString("");
        actionPanel.add(progressBar, BorderLayout.CENTER);
        actionPanel.add(printButton, BorderLayout.EAST);
        bottomPanel.add(actionPanel, BorderLayout.SOUTH);

        add(filePanel, BorderLayout.CENTER);
        add(settingsPanel, BorderLayout.NORTH);
        add(bottomPanel, BorderLayout.SOUTH);

        // --- Actions ---
        addButton.addActionListener(e -> addFiles());
        removeButton.addActionListener(e -> removeSelected());
        printButton.addActionListener(e -> startPrinting());
    }

    HashPrintRequestAttributeSet buildPrintAttributes() {
        HashPrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();

        // Copies
        attrs.add(new Copies((int) copiesSpinner.getValue()));

        // Recto/Verso
        switch (rectoVersoCombo.getSelectedIndex()) {
            case 1 -> attrs.add(Sides.TWO_SIDED_LONG_EDGE);
            case 2 -> attrs.add(Sides.TWO_SIDED_SHORT_EDGE);
            default -> attrs.add(Sides.ONE_SIDED);
        }

        // Couleur
        if (colorCombo.getSelectedIndex() == 1) {
            attrs.add(Chromaticity.MONOCHROME);
        } else {
            attrs.add(Chromaticity.COLOR);
        }

        // Orientation
        if (orientationCombo.getSelectedIndex() == 1) {
            attrs.add(javax.print.attribute.standard.OrientationRequested.LANDSCAPE);
        } else {
            attrs.add(javax.print.attribute.standard.OrientationRequested.PORTRAIT);
        }

        // Pages par feuille
        int pagesPerSheet = Integer.parseInt((String) pagesPerSheetCombo.getSelectedItem());
        if (pagesPerSheet > 1) {
            attrs.add(new javax.print.attribute.standard.NumberUp(pagesPerSheet));
        }

        return attrs;
    }

    private void importFiles(List<File> files) {
        List<String> rejected = new ArrayList<>();
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".zip")) {
                extractFromZip(f, rejected);
            } else if (PdfPrintService.isSupported(f)) {
                if (!fileListModel.contains(f)) {
                    fileListModel.addElement(f);
                }
            } else {
                rejected.add(f.getName());
            }
        }
        if (!rejected.isEmpty()) {
            logArea.append("Fichiers ignorés (format non supporté) :\n");
            for (String name : rejected) {
                logArea.append("  - " + name + "\n");
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
    }

    private void extractFromZip(File zipFile, List<String> rejected) {
        try {
            Path tempDir = Files.createTempDirectory("pdf-batch-printer-");
            tempDir.toFile().deleteOnExit();

            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String entryName = entry.getName();
                    if (entryName.contains("__MACOSX") || entryName.startsWith(".")) continue;

                    String fileName = Path.of(entryName).getFileName().toString();
                    File extracted = tempDir.resolve(fileName).toFile();
                    extracted.deleteOnExit();

                    try (FileOutputStream fos = new FileOutputStream(extracted)) {
                        zis.transferTo(fos);
                    }

                    if (PdfPrintService.isSupported(extracted)) {
                        if (!fileListModel.contains(extracted)) {
                            fileListModel.addElement(extracted);
                        }
                    } else {
                        rejected.add(fileName + " (dans " + zipFile.getName() + ")");
                        extracted.delete();
                    }
                }
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Erreur lors de l'extraction du ZIP : " + ex.getMessage(),
                    "Erreur", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyTheme(String themeName) {
        MaterialTheme theme = THEMES.get(themeName);
        if (theme == null) return;
        try {
            UIManager.setLookAndFeel(new MaterialLookAndFeel(theme));
            SwingUtilities.updateComponentTreeUI(this);
            PREFS.put(PREF_THEME, themeName);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Impossible d'appliquer le thème : " + ex.getMessage(),
                    "Erreur", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static Image createRoundIcon(Image source, int size) {
        java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        // Clip en cercle
        g2.setClip(new java.awt.geom.Ellipse2D.Float(0, 0, size, size));

        // Recadrer au centre (carré) puis dessiner
        int srcW = source.getWidth(null);
        int srcH = source.getHeight(null);
        int cropSize = Math.min(srcW, srcH);
        int sx = (srcW - cropSize) / 2;
        int sy = (srcH - cropSize) / 2;

        g2.drawImage(source, 0, 0, size, size, sx, sy, sx + cropSize, sy + cropSize, null);
        g2.dispose();
        return scaled;
    }

    private void addFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
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
        PrintService printer = (PrintService) printerCombo.getSelectedItem();
        if (printer == null) {
            JOptionPane.showMessageDialog(this, "Aucune imprimante disponible.", "Erreur", JOptionPane.ERROR_MESSAGE);
            return;
        }
        int batchSize = (int) batchSizeSpinner.getValue();
        HashPrintRequestAttributeSet printAttrs = buildPrintAttributes();

        List<File> files = new ArrayList<>();
        for (int i = 0; i < fileListModel.size(); i++) {
            files.add(fileListModel.get(i));
        }

        setControlsEnabled(false);
        logArea.setText("");
        progressBar.setValue(0);
        progressBar.setMaximum(files.size());

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                PdfPrintService service = new PdfPrintService();
                for (int i = 0; i < files.size(); i++) {
                    File file = files.get(i);
                    try {
                        service.print(file, batchSize, printer, printAttrs, progress -> {
                            publish(String.format("[%s] Lot %d/%d (pages %d à %d) envoyé",
                                    progress.fileName(), progress.batchNumber(), progress.totalBatches(),
                                    progress.fromPage(), progress.toPage()));
                        });
                        int current = i + 1;
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setValue(current);
                            progressBar.setString(current + " / " + files.size());
                        });
                    } catch (Exception ex) {
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        publish("ERREUR [" + file.getName() + "] : " + msg);
                        ex.printStackTrace();
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
                logArea.setCaretPosition(logArea.getDocument().getLength());
                progressBar.setString("Terminé");
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String savedTheme = PREFS.get(PREF_THEME, DEFAULT_THEME);
            MaterialTheme theme = THEMES.getOrDefault(savedTheme, THEMES.get(DEFAULT_THEME));
            try {
                UIManager.setLookAndFeel(new MaterialLookAndFeel(theme));
            } catch (Exception ignored) {}
            new App().setVisible(true);
        });
    }
}
