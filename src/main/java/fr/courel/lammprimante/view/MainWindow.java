package fr.courel.lammprimante.view;

import fr.courel.lammprimante.App;
import fr.courel.lammprimante.config.PreferencesManager;
import fr.courel.lammprimante.config.ThemeManager;
import fr.courel.lammprimante.model.PrintSettings;
import fr.courel.lammprimante.service.FileImportService;
import fr.courel.lammprimante.service.LogService;
import fr.courel.lammprimante.service.PrintService;
import fr.courel.lammui.component.LammButton;
import fr.courel.lammui.component.LammCard;
import fr.courel.lammui.component.LammComboBox;
import fr.courel.lammui.component.LammDialog;
import fr.courel.lammui.component.LammFrame;
import fr.courel.lammui.component.LammHeader;
import fr.courel.lammui.component.LammLabel;
import fr.courel.lammui.component.LammProgressBar;
import fr.courel.lammui.component.LammScrollPane;
import fr.courel.lammui.component.LammSpinner;
import fr.courel.lammui.component.LammSwitch;
import fr.courel.lammui.component.LammTextArea;
import fr.courel.lammui.component.LammTitle;
import fr.courel.lammui.component.LammTree;
import fr.courel.lammui.theme.LammTheme;

import javax.imageio.ImageIO;
import javax.print.PrintServiceLookup;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainWindow extends LammFrame {

    private final FileImportService fileImportService = new FileImportService();

    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Fichiers");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final LammTree fileTree = new LammTree(treeModel);
    private final List<File> allFiles = new ArrayList<>();
    private final Map<File, Integer> resumePages = new HashMap<>();

    private final LammComboBox<PrinterItem> printerCombo;

    private record PrinterItem(javax.print.PrintService service) {
        @Override
        public String toString() {
            return service != null ? service.getName() : "—";
        }
    }
    private final LammSpinner batchSizeSpinner = new LammSpinner("Pages par lot", 15, 1, 100, 1);
    private final LammSpinner copiesSpinner = new LammSpinner("Copies", 1, 1, 99, 1);
    private final LammComboBox<String> rectoVersoCombo = new LammComboBox<>("Recto/Verso",
            "Recto seul", "Recto/Verso (bord long)", "Recto/Verso (bord court)");
    private final LammComboBox<String> pagesPerSheetCombo = new LammComboBox<>("Pages par feuille",
            "1", "2", "4");
    private final LammComboBox<String> orientationCombo = new LammComboBox<>("Orientation",
            "Portrait", "Paysage");
    private final LammComboBox<String> colorCombo = new LammComboBox<>("Couleur",
            "Couleur", "Noir et blanc");

    private final LammTextArea logArea = new LammTextArea("Journal", 6, 40);
    private final LammButton printButton = new LammButton("Imprimer");
    private final LammButton cancelButton = LammButton.flat("Annuler");
    private final LammButton addButton = LammButton.flat("Ajouter des fichiers…");
    private final LammButton removeButton = LammButton.flat("Retirer");
    private final LammProgressBar progressBar = new LammProgressBar();
    private SwingWorker<Void, String> currentWorker;

    public MainWindow() {
        super("Lammprimante v" + App.getVersion());
        setMinimumSize(new Dimension(700, 600));
        applySavedBounds();
        loadIcon();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                PreferencesManager.setWindowBounds(getX(), getY(), getWidth(), getHeight());
                fileImportService.cleanup();
            }
        });

        printerCombo = buildPrinterCombo();
        progressBar.setStringPainted(true);

        batchSizeSpinner.setValue(PreferencesManager.getBatchSize());
        rectoVersoCombo.getCombo().setSelectedIndex(PreferencesManager.getDuplex());
        orientationCombo.getCombo().setSelectedIndex(PreferencesManager.getOrientation());
        colorCombo.getCombo().setSelectedIndex(PreferencesManager.getColor());

        batchSizeSpinner.getSpinner().addChangeListener(_ ->
                PreferencesManager.setBatchSize(batchSizeSpinner.getValue()));
        rectoVersoCombo.getCombo().addActionListener(_ ->
                PreferencesManager.setDuplex(rectoVersoCombo.getCombo().getSelectedIndex()));
        orientationCombo.getCombo().addActionListener(_ ->
                PreferencesManager.setOrientation(orientationCombo.getCombo().getSelectedIndex()));
        colorCombo.getCombo().addActionListener(_ ->
                PreferencesManager.setColor(colorCombo.getCombo().getSelectedIndex()));

        fileTree.setRootVisible(false);
        fileTree.setShowsRootHandles(true);
        logArea.setEditable(false);

        setLayout(new BorderLayout());
        add(buildHeader(), BorderLayout.NORTH);
        add(buildContent(), BorderLayout.CENTER);

        addButton.addActionListener(_ -> addFiles());
        removeButton.addActionListener(_ -> removeSelected());
        printButton.addActionListener(_ -> startPrinting());
        cancelButton.addActionListener(_ -> cancelPrinting());
        cancelButton.setEnabled(false);
    }

    private void applySavedBounds() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int maxW = Math.max(700, screen.width - 80);
        int maxH = Math.max(600, screen.height - 120);

        int w = Math.min(Math.max(PreferencesManager.getWindowWidth(), 700), maxW);
        int h = Math.min(Math.max(PreferencesManager.getWindowHeight(), 600), maxH);
        setSize(w, h);

        int x = PreferencesManager.getWindowX();
        int y = PreferencesManager.getWindowY();
        boolean onScreen = x >= 0 && y >= 0 && x + w <= screen.width && y + h <= screen.height;
        if (onScreen) {
            setLocation(x, y);
        } else {
            setLocationRelativeTo(null);
        }
    }

    private JPanel buildHeader() {
        var header = new LammHeader();
        header.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));

        var titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titlePanel.setOpaque(false);
        titlePanel.add(new LammTitle("primante", 22f));
        header.add(titlePanel, BorderLayout.WEST);

        var themeSwitch = new LammSwitch(LammTheme.isDark() ? "Light" : "Dark");
        themeSwitch.setOnGradient(true);
        themeSwitch.setSelected(LammTheme.isDark());
        themeSwitch.addPropertyChangeListener("selected", _ -> {
            LammTheme.toggle();
            themeSwitch.setLabel(LammTheme.isDark() ? "Light" : "Dark");
            ThemeManager.save(LammTheme.isDark());
            LammTheme.repaintAll(this);
        });
        var switchWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        switchWrapper.setOpaque(false);
        switchWrapper.add(themeSwitch);
        header.add(switchWrapper, BorderLayout.EAST);

        return header;
    }

    private JPanel buildContent() {
        var content = new JPanel(new BorderLayout(12, 12));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 20, 20));

        content.add(buildSettingsCard(), BorderLayout.NORTH);
        content.add(buildFilesCard(), BorderLayout.CENTER);
        content.add(buildBottom(), BorderLayout.SOUTH);

        return content;
    }

    private void loadIcon() {
        try {
            Image source = ImageIO.read(getClass().getResourceAsStream("/logo.jpg"));
            int size = 64;
            var icon = new java.awt.image.BufferedImage(size, size,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
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
        } catch (Exception ignored) {
        }
    }

    private LammComboBox<PrinterItem> buildPrinterCombo() {
        javax.print.PrintService[] printers = PrintServiceLookup.lookupPrintServices(null, null);
        PrinterItem[] items = new PrinterItem[printers.length];
        for (int i = 0; i < printers.length; i++) {
            items[i] = new PrinterItem(printers[i]);
        }
        var combo = new LammComboBox<>("Imprimante", items);
        javax.print.PrintService defaultPrinter = PrintServiceLookup.lookupDefaultPrintService();
        if (defaultPrinter != null) {
            for (var item : items) {
                if (defaultPrinter.equals(item.service())) {
                    combo.getCombo().setSelectedItem(item);
                    break;
                }
            }
        }
        return combo;
    }

    private JPanel buildFilesCard() {
        var card = new LammCard();
        card.setLayout(new BorderLayout(0, 8));
        card.setTitle("Fichiers (glisser-déposer PDF, images, ZIP ou dossiers ici)");

        card.add(new LammScrollPane(fileTree), BorderLayout.CENTER);

        new DropTarget(fileTree, new DropTargetAdapter() {
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

        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(addButton);
        buttons.add(removeButton);
        card.add(buttons, BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildSettingsCard() {
        var card = new LammCard();
        card.setLayout(new GridBagLayout());
        card.setTitle("Paramètres");

        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 6, 2, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        card.add(printerCombo, gbc);
        gbc.gridwidth = 1;
        gbc.gridx = 2; gbc.gridy = 0; card.add(batchSizeSpinner, gbc);
        gbc.gridx = 3;                 card.add(copiesSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 1; card.add(rectoVersoCombo, gbc);
        gbc.gridx = 1;                 card.add(orientationCombo, gbc);
        gbc.gridx = 2;                 card.add(colorCombo, gbc);
        gbc.gridx = 3;                 card.add(pagesPerSheetCombo, gbc);

        return card;
    }

    private JPanel buildBottom() {
        var wrapper = new JPanel(new BorderLayout(0, 8));
        wrapper.setOpaque(false);

        var logsCard = new LammCard();
        logsCard.setLayout(new BorderLayout());
        logsCard.setTitle("Journal");
        logsCard.add(logArea, BorderLayout.CENTER);
        wrapper.add(logsCard, BorderLayout.CENTER);

        var actionRow = new JPanel(new BorderLayout(12, 0));
        actionRow.setOpaque(false);
        actionRow.add(progressBar, BorderLayout.CENTER);

        var btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);
        btns.add(cancelButton);
        btns.add(printButton);
        actionRow.add(btns, BorderLayout.EAST);
        wrapper.add(actionRow, BorderLayout.SOUTH);

        return wrapper;
    }

    private PrintSettings buildPrintSettings() {
        PrintSettings.DuplexMode duplex = switch (rectoVersoCombo.getCombo().getSelectedIndex()) {
            case 1 -> PrintSettings.DuplexMode.LONG_EDGE;
            case 2 -> PrintSettings.DuplexMode.SHORT_EDGE;
            default -> PrintSettings.DuplexMode.RECTO;
        };

        return new PrintSettings(
                batchSizeSpinner.getValue(),
                copiesSpinner.getValue(),
                duplex,
                orientationCombo.getCombo().getSelectedIndex() == 1
                        ? PrintSettings.Orientation.LANDSCAPE : PrintSettings.Orientation.PORTRAIT,
                colorCombo.getCombo().getSelectedIndex() == 1
                        ? PrintSettings.ColorMode.MONOCHROME : PrintSettings.ColorMode.COLOR,
                Integer.parseInt((String) pagesPerSheetCombo.getSelectedItem())
        );
    }

    private void importFiles(List<File> files) {
        LogService.info("Import de " + files.size() + " élément(s)");
        appendLog("Import en cours...");
        setControlsEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setString("Chargement…");

        SwingWorker<FileImportService.ImportResult, Void> worker = new SwingWorker<>() {
            @Override
            protected FileImportService.ImportResult doInBackground() {
                return fileImportService.importFiles(files, displayName -> {});
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                progressBar.setString("");
                progressBar.setValue(0);
                try {
                    FileImportService.ImportResult result = get();

                    for (File f : result.accepted()) {
                        if (!allFiles.contains(f)) {
                            allFiles.add(f);
                        }
                        resumePages.remove(f);
                    }
                    rebuildTree();

                    if (!result.rejected().isEmpty()) {
                        appendLog("Fichiers ignorés (format non supporté) :");
                        for (String name : result.rejected()) {
                            appendLog("  - " + name);
                            LogService.info("Fichier ignoré : " + name);
                        }
                    }

                    if (!result.errors().isEmpty()) {
                        for (String error : result.errors()) {
                            appendLog("ERREUR : " + error);
                            LogService.error(error);
                        }
                    }

                    appendLog("Import terminé : " + result.accepted().size() + " fichier(s) ajouté(s)");
                    LogService.info("Import terminé : " + result.accepted().size() + " fichier(s)");
                } catch (Exception ex) {
                    appendLog("ERREUR : " + ex.getMessage());
                    LogService.error("Erreur lors de l'import", ex);
                }
                setControlsEnabled(true);
            }
        };
        worker.execute();
    }

    private void rebuildTree() {
        treeRoot.removeAllChildren();
        Map<String, DefaultMutableTreeNode> folderNodes = new LinkedHashMap<>();

        for (File f : allFiles) {
            String displayName = fileImportService.getDisplayName(f);
            String[] parts = displayName.split("/");

            if (parts.length == 1) {
                treeRoot.add(new DefaultMutableTreeNode(parts[0]));
            } else {
                DefaultMutableTreeNode parent = treeRoot;
                StringBuilder pathKey = new StringBuilder();
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0) pathKey.append("/");
                    pathKey.append(parts[i]);
                    String key = pathKey.toString();

                    if (!folderNodes.containsKey(key)) {
                        var folderNode = new DefaultMutableTreeNode(parts[i]);
                        parent.add(folderNode);
                        folderNodes.put(key, folderNode);
                    }
                    parent = folderNodes.get(key);
                }
                parent.add(new DefaultMutableTreeNode(parts[parts.length - 1]));
            }
        }

        treeModel.reload();
        for (int i = 0; i < fileTree.getRowCount(); i++) {
            fileTree.expandRow(i);
        }
    }

    private void addFiles() {
        var chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setFileFilter(new FileNameExtensionFilter("PDF, images et ZIP",
                "pdf", "zip", "jpg", "jpeg", "png", "bmp", "gif", "tiff", "tif"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            importFiles(List.of(chooser.getSelectedFiles()));
        }
    }

    private void removeSelected() {
        var paths = fileTree.getSelectionPaths();
        if (paths == null) return;

        Set<String> removedDisplayNames = new HashSet<>();
        for (var path : paths) {
            StringBuilder name = new StringBuilder();
            for (int i = 1; i < path.getPathCount(); i++) {
                if (i > 1) name.append("/");
                name.append(path.getPathComponent(i).toString());
            }
            removedDisplayNames.add(name.toString());
        }

        allFiles.removeIf(f -> {
            String dn = fileImportService.getDisplayName(f);
            for (String sel : removedDisplayNames) {
                if (dn.equals(sel) || dn.startsWith(sel + "/")) {
                    resumePages.remove(f);
                    return true;
                }
            }
            return false;
        });

        rebuildTree();
    }

    private void startPrinting() {
        if (allFiles.isEmpty()) {
            LammDialog.warning(this, "Attention", "Aucun fichier sélectionné.");
            return;
        }
        PrinterItem item = printerCombo.getSelectedItem();
        javax.print.PrintService printer = item != null ? item.service() : null;
        if (printer == null) {
            LammDialog.error(this, "Erreur", "Aucune imprimante disponible.");
            return;
        }
        PrintSettings settings = buildPrintSettings();

        final List<File> files = new ArrayList<>(allFiles);
        final int total = files.size();

        setControlsEnabled(false);
        cancelButton.setEnabled(true);
        logArea.setText("");
        progressBar.setValue(0);
        progressBar.setString("0 / " + total);

        LogService.info("Lancement impression : " + total + " fichier(s), imprimante=" + printer.getName());

        List<File> failedFiles = new ArrayList<>();
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                PrintService service = new PrintService();
                for (int i = 0; i < total; i++) {
                    if (isCancelled() || Thread.currentThread().isInterrupted()) {
                        return null;
                    }
                    final int fileIndex = i;
                    File file = files.get(i);
                    int startPage = resumePages.getOrDefault(file, 0);
                    try {
                        service.print(file, printer, settings, startPage, progress -> {
                            String msg = String.format("[%s] Lot %d/%d (pages %d à %d) envoyé",
                                    progress.fileName(), progress.batchNumber(), progress.totalBatches(),
                                    progress.fromPage(), progress.toPage());
                            publish(msg);
                            LogService.info(msg);

                            float fileFrac = progress.totalBatches() > 0
                                    ? (float) progress.batchNumber() / progress.totalBatches()
                                    : 0f;
                            float overall = (fileIndex + fileFrac) / total;
                            SwingUtilities.invokeLater(() -> progressBar.setValue(overall));
                        });
                        resumePages.remove(file);
                        int current = i + 1;
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setValue((float) current / total);
                            progressBar.setString(current + " / " + total);
                        });
                    } catch (PrintService.PartialPrintException ex) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                        int resume = ex.getResumePage();
                        resumePages.put(file, resume);
                        String raw = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        String friendly = "Le spooler a interrompu l'envoi après plusieurs tentatives — reprise prévue à la page " + (resume + 1);
                        publish("ERREUR [" + file.getName() + "] : " + friendly);
                        LogService.error("Impression partielle pour " + file.getName() + " — reprise page " + (resume + 1) + " — " + raw, ex);
                        failedFiles.add(file);
                    } catch (java.awt.print.PrinterException ex) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                        String raw = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        String friendly = "Le spooler a interrompu l'envoi après plusieurs tentatives (imprimante occupée ou hors ligne)";
                        publish("ERREUR [" + file.getName() + "] : " + friendly);
                        LogService.error("Impression échouée pour " + file.getName() + " — " + raw, ex);
                        failedFiles.add(file);
                    } catch (Exception ex) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        publish("ERREUR [" + file.getName() + "] : " + msg);
                        LogService.error("Impression échouée pour " + file.getName(), ex);
                        failedFiles.add(file);
                    }
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
            }

            @Override
            protected void done() {
                setControlsEnabled(true);
                cancelButton.setEnabled(false);
                currentWorker = null;
                if (isCancelled()) {
                    appendLog("--- Annulé ---");
                    progressBar.setString("Annulé");
                    LogService.info("Impression annulée par l'utilisateur");
                } else {
                    appendLog("--- Terminé ---");
                    if (!failedFiles.isEmpty()) {
                        appendLog("Détails des erreurs dans : " + LogService.getLogFile());
                        appendLog(failedFiles.size() + " fichier(s) non imprimé(s) rechargé(s) — cliquer sur Imprimer pour réessayer");
                        allFiles.clear();
                        allFiles.addAll(failedFiles);
                        rebuildTree();
                        progressBar.setString(failedFiles.size() + " en échec");
                        LogService.info("Impression terminée avec " + failedFiles.size() + " échec(s), fichiers rechargés dans la liste");
                    } else {
                        progressBar.setString("Terminé");
                        LogService.info("Impression terminée");
                    }
                }
            }
        };
        currentWorker = worker;
        worker.execute();
    }

    private void cancelPrinting() {
        if (currentWorker == null || currentWorker.isDone()) return;
        cancelButton.setEnabled(false);
        appendLog("Annulation demandée — le lot en cours va se terminer…");
        LogService.info("Annulation demandée");
        currentWorker.cancel(true);
    }

    private void appendLog(String message) {
        var area = logArea.getTextArea();
        area.append(message + "\n");
        area.setCaretPosition(area.getDocument().getLength());
    }

    private void setControlsEnabled(boolean enabled) {
        printButton.setEnabled(enabled);
        addButton.setEnabled(enabled);
        removeButton.setEnabled(enabled);
        printerCombo.getCombo().setEnabled(enabled);
        batchSizeSpinner.getSpinner().setEnabled(enabled);
        copiesSpinner.getSpinner().setEnabled(enabled);
        rectoVersoCombo.getCombo().setEnabled(enabled);
        pagesPerSheetCombo.getCombo().setEnabled(enabled);
        orientationCombo.getCombo().setEnabled(enabled);
        colorCombo.getCombo().setEnabled(enabled);
    }
}
