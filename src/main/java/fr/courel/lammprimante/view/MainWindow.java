package fr.courel.lammprimante.view;

import fr.courel.lammprimante.App;
import fr.courel.lammprimante.config.PreferencesManager;
import fr.courel.lammprimante.model.PrintSettings;
import fr.courel.lammprimante.service.BatchPlanner;
import fr.courel.lammprimante.service.FileImportService;
import fr.courel.lammprimante.service.LogService;
import fr.courel.lammprimante.service.PrintService;
import fr.courel.lammui.fx.component.LammButtonFx;
import fr.courel.lammui.fx.component.LammCardFx;
import fr.courel.lammui.fx.component.LammProgressBarFx;
import fr.courel.lammui.fx.component.LammSpinnerFx;
import fr.courel.lammui.fx.component.LammTreeFx;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeItem;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import javax.print.PrintServiceLookup;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainWindow extends VBox {

    private final App app;
    private final FileImportService fileImportService = new FileImportService();
    private final List<File> allFiles = new ArrayList<>();

    private final Map<File, PrintService.ResumePoint> resumePoints = new HashMap<>();
    private final Map<File, Integer> pageCounts = new HashMap<>();
    private final Map<File, String> passwords = new HashMap<>();
    private final Map<File, PrintService.PageRange> ranges = new HashMap<>();
    private final Map<TreeItem<String>, File> leafItems = new HashMap<>();

    private final TreeItem<String> treeRoot = new TreeItem<>("Fichiers");
    private final LammTreeFx<String> fileTree = new LammTreeFx<>(treeRoot);

    private final ComboBox<PrinterItem> printerCombo;
    private final LammSpinnerFx batchSizeSpinner;
    private final LammSpinnerFx copiesSpinner;
    private final ComboBox<String> rectoVersoCombo;
    private final ComboBox<String> pagesPerSheetCombo;
    private final ComboBox<String> orientationCombo;
    private final ComboBox<String> colorCombo;
    private final CheckBox groupImagesCheck = new CheckBox("Regrouper les images en un document");

    private final TextArea logArea = new TextArea();
    private final LammButtonFx printButton = LammButtonFx.primary("Imprimer");
    private final LammButtonFx cancelButton = new LammButtonFx("Annuler");
    private final LammButtonFx addButton = new LammButtonFx("Ajouter des fichiers…");
    private final LammButtonFx removeButton = new LammButtonFx("Retirer");
    private final LammButtonFx moveUpButton = new LammButtonFx("↑");
    private final LammButtonFx moveDownButton = new LammButtonFx("↓");
    private final LammProgressBarFx progressBar = new LammProgressBarFx(0);
    private final Label progressLabel = new Label();
    private final Label summaryLabel = new Label();

    private Task<Void> currentTask;

    private record PrinterItem(javax.print.PrintService service) {
        @Override
        public String toString() {
            return service != null ? service.getName() : "—";
        }
    }

    /** Unité d'impression : un fichier, ou un groupe d'images fusionnées en un document. */
    private record PrintUnit(String label, File file, List<File> images) {
        static PrintUnit single(File f) {
            return new PrintUnit(f.getName(), f, null);
        }
        static PrintUnit imageGroup(List<File> images) {
            return new PrintUnit("Images (" + images.size() + ")", null, List.copyOf(images));
        }
        List<File> allFiles() {
            return file != null ? List.of(file) : images;
        }
        File resumeKey() {
            return file != null ? file : images.get(0);
        }
    }

    public MainWindow(App app) {
        this.app = app;

        printerCombo = buildPrinterCombo();
        batchSizeSpinner = new LammSpinnerFx("Pages par lot", 1, 100, PreferencesManager.getBatchSize());
        copiesSpinner = new LammSpinnerFx("Copies", 1, 99, 1);
        rectoVersoCombo = new ComboBox<>(FXCollections.observableArrayList(
            "Recto seul", "Recto/Verso — Livre", "Recto/Verso — Bloc-notes"));
        rectoVersoCombo.getSelectionModel().select(PreferencesManager.getDuplex());
        pagesPerSheetCombo = new ComboBox<>(FXCollections.observableArrayList("1", "2", "4"));
        pagesPerSheetCombo.getSelectionModel().selectFirst();
        orientationCombo = new ComboBox<>(FXCollections.observableArrayList("Portrait", "Paysage"));
        orientationCombo.getSelectionModel().select(PreferencesManager.getOrientation());
        colorCombo = new ComboBox<>(FXCollections.observableArrayList("Couleur", "Noir et blanc"));
        colorCombo.getSelectionModel().select(PreferencesManager.getColor());
        groupImagesCheck.setSelected(PreferencesManager.getGroupImages());

        wirePreferenceListeners();
        wireSummaryListeners();
        snapBatchParity();

        treeRoot.setExpanded(true);
        fileTree.setShowRoot(false);
        fileTree.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        fileTree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editRangeForSelection();
            }
        });

        logArea.setEditable(false);
        logArea.setPrefRowCount(6);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        cancelButton.setDisable(true);

        addButton.setOnAction(e -> addFiles());
        removeButton.setOnAction(e -> removeSelected());
        moveUpButton.setOnAction(e -> moveSelected(-1));
        moveDownButton.setOnAction(e -> moveSelected(1));
        printButton.setOnAction(e -> startPrinting());
        cancelButton.setOnAction(e -> cancelPrinting());

        installDragAndDrop();

        setSpacing(12);
        setPadding(new Insets(16, 20, 20, 20));
        getChildren().addAll(buildSettingsCard(), buildFilesCard(), buildBottom());
        VBox.setVgrow(getChildren().get(1), Priority.ALWAYS);
        updateSummary();
    }

    public void shutdown() {
        if (currentTask != null && currentTask.isRunning()) {
            currentTask.cancel();
        }
        fileImportService.cleanup();
    }

    /* ---------- Layout helpers ---------- */

    private static VBox labeled(String label, Node field) {
        var l = new Label(label);
        l.getStyleClass().add("lamm-spinner-label");
        var box = new VBox(2, l, field);
        if (field instanceof Region r) {
            r.setMaxWidth(Double.MAX_VALUE);
        }
        return box;
    }

    private LammCardFx buildSettingsCard() {
        var card = new LammCardFx("Paramètres");
        var grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        for (int i = 0; i < 4; i++) {
            var col = new javafx.scene.layout.ColumnConstraints();
            col.setPercentWidth(25);
            col.setHgrow(Priority.SOMETIMES);
            grid.getColumnConstraints().add(col);
        }

        grid.add(labeled("Imprimante", printerCombo), 0, 0, 2, 1);
        grid.add(batchSizeSpinner, 2, 0);
        grid.add(copiesSpinner, 3, 0);
        grid.add(labeled("Recto/Verso", rectoVersoCombo), 0, 1);
        grid.add(labeled("Orientation", orientationCombo), 1, 1);
        grid.add(labeled("Couleur", colorCombo), 2, 1);
        grid.add(labeled("Pages par feuille", pagesPerSheetCombo), 3, 1);
        grid.add(groupImagesCheck, 0, 2, 2, 1);

        card.getChildren().add(grid);
        return card;
    }

    private LammCardFx buildFilesCard() {
        var card = new LammCardFx("Fichiers (glisser-déposer PDF, images, ZIP ou dossiers — double-clic : plage de pages)");
        var scroll = new ScrollPane(fileTree);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(180);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        summaryLabel.getStyleClass().add("lamm-subtitle");
        var buttons = new HBox(8, addButton, removeButton, moveUpButton, moveDownButton, spacer, summaryLabel);
        buttons.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(scroll, buttons);
        VBox.setVgrow(card, Priority.ALWAYS);
        return card;
    }

    private VBox buildBottom() {
        var logsCard = new LammCardFx("Journal");
        VBox.setVgrow(logArea, Priority.ALWAYS);
        logsCard.getChildren().add(logArea);

        HBox.setHgrow(progressBar, Priority.ALWAYS);
        var actionRow = new HBox(12, progressBar, progressLabel, cancelButton, printButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        progressLabel.setMinWidth(140);

        var wrapper = new VBox(8, logsCard, actionRow);
        return wrapper;
    }

    /* ---------- Init helpers ---------- */

    private ComboBox<PrinterItem> buildPrinterCombo() {
        javax.print.PrintService[] printers = PrintServiceLookup.lookupPrintServices(null, null);
        var items = FXCollections.<PrinterItem>observableArrayList();
        for (var p : printers) items.add(new PrinterItem(p));
        var combo = new ComboBox<>(items);
        javax.print.PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        if (def != null) {
            for (var item : items) {
                if (def.equals(item.service())) {
                    combo.getSelectionModel().select(item);
                    break;
                }
            }
        }
        if (combo.getSelectionModel().isEmpty() && !items.isEmpty()) {
            combo.getSelectionModel().selectFirst();
        }
        return combo;
    }

    private void wirePreferenceListeners() {
        batchSizeSpinner.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                PreferencesManager.setBatchSize(nv);
                Platform.runLater(this::snapBatchParity);
            }
        });
        rectoVersoCombo.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) -> {
            PreferencesManager.setDuplex(nv.intValue());
            Platform.runLater(this::snapBatchParity);
        });
        orientationCombo.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) ->
            PreferencesManager.setOrientation(nv.intValue()));
        colorCombo.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) ->
            PreferencesManager.setColor(nv.intValue()));
        groupImagesCheck.selectedProperty().addListener((o, ov, nv) ->
            PreferencesManager.setGroupImages(nv));
    }

    private void wireSummaryListeners() {
        rectoVersoCombo.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) -> updateSummary());
        pagesPerSheetCombo.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) -> updateSummary());
        copiesSpinner.valueProperty().addListener((o, ov, nv) -> updateSummary());
        groupImagesCheck.selectedProperty().addListener((o, ov, nv) -> updateSummary());
    }

    private boolean duplexSelected() {
        return rectoVersoCombo.getSelectionModel().getSelectedIndex() != 0;
    }

    /**
     * En duplex, garde le spinner sur une valeur paire : un lot impair est de
     * toute façon ramené à pair par le moteur, autant l'afficher honnêtement.
     */
    private void snapBatchParity() {
        int v = batchSizeSpinner.getValue();
        if (duplexSelected() && v % 2 != 0) {
            batchSizeSpinner.setValue(v == 1 ? 2 : Math.min(100, v + 1));
        }
    }

    private void installDragAndDrop() {
        setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        setOnDragDropped(e -> {
            var db = e.getDragboard();
            if (db.hasFiles()) {
                importFiles(db.getFiles());
                e.setDropCompleted(true);
            }
            e.consume();
        });
    }

    /* ---------- File handling ---------- */

    private void addFiles() {
        var chooser = new FileChooser();
        chooser.setTitle("Ajouter des fichiers");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            "PDF, images et ZIP",
            "*.pdf", "*.zip", "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.gif", "*.tiff", "*.tif"));
        var selected = chooser.showOpenMultipleDialog(app.stage());
        if (selected != null && !selected.isEmpty()) {
            importFiles(selected);
        }
    }

    private void removeSelected() {
        var selectedItems = fileTree.getSelectionModel().getSelectedItems();
        if (selectedItems == null || selectedItems.isEmpty()) return;

        Set<File> toRemove = new HashSet<>();
        for (var item : new ArrayList<>(selectedItems)) {
            collectFiles(item, toRemove);
        }
        allFiles.removeAll(toRemove);
        for (File f : toRemove) {
            forgetFile(f);
        }
        rebuildTree();
    }

    private void collectFiles(TreeItem<String> item, Set<File> out) {
        File f = leafItems.get(item);
        if (f != null) {
            out.add(f);
        } else {
            for (var child : item.getChildren()) {
                collectFiles(child, out);
            }
        }
    }

    private void forgetFile(File f) {
        resumePoints.remove(f);
        pageCounts.remove(f);
        passwords.remove(f);
        ranges.remove(f);
    }

    private void importFiles(List<File> files) {
        LogService.info("Import de " + files.size() + " élément(s)");
        appendLog("Import en cours...");
        setControlsEnabled(false);
        progressBar.setProgress(-1);
        progressLabel.setText("Chargement…");

        record Analyzed(FileImportService.ImportResult result, Map<File, FileImportService.FileInfo> infos) {}
        var task = new Task<Analyzed>() {
            @Override
            protected Analyzed call() {
                FileImportService.ImportResult result = fileImportService.importFiles(files, displayName -> {});
                Map<File, FileImportService.FileInfo> infos = new HashMap<>();
                for (File f : result.accepted()) {
                    infos.put(f, FileImportService.analyze(f, null));
                }
                return new Analyzed(result, infos);
            }
        };
        task.setOnSucceeded(e -> {
            progressBar.setProgress(0);
            progressLabel.setText("");
            FileImportService.ImportResult result = task.getValue().result();
            Map<File, FileImportService.FileInfo> infos = task.getValue().infos();
            int added = 0;
            for (File f : result.accepted()) {
                FileImportService.FileInfo info = infos.get(f);
                if (info != null && info.encrypted() && !askPassword(f)) {
                    appendLog("Ignoré (mot de passe manquant) : " + fileImportService.getDisplayName(f));
                    LogService.info("Fichier chiffré ignoré : " + f.getName());
                    continue;
                }
                if (!allFiles.contains(f)) {
                    allFiles.add(f);
                    added++;
                }
                resumePoints.remove(f);
                if (info != null && !info.encrypted()) {
                    pageCounts.put(f, info.pages());
                }
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
            appendLog("Import terminé : " + added + " fichier(s) ajouté(s)");
            LogService.info("Import terminé : " + added + " fichier(s)");
            setControlsEnabled(true);
        });
        task.setOnFailed(e -> {
            progressBar.setProgress(0);
            progressLabel.setText("");
            Throwable ex = task.getException();
            appendLog("ERREUR : " + (ex == null ? "?" : ex.getMessage()));
            LogService.error("Erreur lors de l'import", ex);
            setControlsEnabled(true);
        });
        var t = new Thread(task, "lammprimante-import");
        t.setDaemon(true);
        t.start();
    }

    /** Demande le mot de passe d'un PDF chiffré. Retourne false si l'utilisateur abandonne. */
    private boolean askPassword(File f) {
        String displayName = fileImportService.getDisplayName(f);
        while (true) {
            var dialog = new TextInputDialog();
            dialog.setTitle("Document protégé");
            dialog.setHeaderText("« " + displayName + " » est protégé par un mot de passe.");
            dialog.setContentText("Mot de passe :");
            dialog.initOwner(app.stage());
            app.styleDialog(dialog);
            var input = dialog.showAndWait();
            if (input.isEmpty() || input.get().isBlank()) {
                return false;
            }
            FileImportService.FileInfo retry = FileImportService.analyze(f, input.get());
            if (!retry.encrypted()) {
                passwords.put(f, input.get());
                pageCounts.put(f, retry.pages());
                return true;
            }
            appendLog("Mot de passe incorrect pour " + displayName);
        }
    }

    private void rebuildTree() {
        treeRoot.getChildren().clear();
        leafItems.clear();
        Map<String, TreeItem<String>> folderNodes = new LinkedHashMap<>();
        for (File f : allFiles) {
            String displayName = fileImportService.getDisplayName(f);
            String[] parts = displayName.split("/");
            var leaf = new TreeItem<>(leafLabel(f, parts[parts.length - 1]));
            leafItems.put(leaf, f);
            if (parts.length == 1) {
                treeRoot.getChildren().add(leaf);
            } else {
                TreeItem<String> parent = treeRoot;
                StringBuilder pathKey = new StringBuilder();
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0) pathKey.append("/");
                    pathKey.append(parts[i]);
                    String key = pathKey.toString();
                    if (!folderNodes.containsKey(key)) {
                        var folderNode = new TreeItem<>(parts[i]);
                        folderNode.setExpanded(true);
                        parent.getChildren().add(folderNode);
                        folderNodes.put(key, folderNode);
                    }
                    parent = folderNodes.get(key);
                }
                parent.getChildren().add(leaf);
            }
        }
        updateSummary();
    }

    private String leafLabel(File f, String baseName) {
        var sb = new StringBuilder(baseName);
        Integer pages = pageCounts.get(f);
        if (pages != null && pages > 0) {
            sb.append(" — ").append(pages).append(" p.");
        }
        var range = ranges.get(f);
        if (range != null) {
            sb.append(" (p. ").append(range.from());
            if (range.to() != range.from()) sb.append("-").append(range.to());
            sb.append(")");
        }
        return sb.toString();
    }

    /* ---------- Réordonnancement ---------- */

    private static String topKey(String displayName) {
        int slash = displayName.indexOf('/');
        return slash < 0 ? displayName : displayName.substring(0, slash);
    }

    /** Déplace le bloc de premier niveau contenant la sélection dans l'ordre d'impression. */
    private void moveSelected(int direction) {
        var item = fileTree.getSelectionModel().getSelectedItem();
        if (item == null) return;
        var top = item;
        while (top.getParent() != null && top.getParent() != treeRoot) {
            top = top.getParent();
        }
        File leafFile = leafItems.get(top);
        String selKey = leafFile != null
            ? topKey(fileImportService.getDisplayName(leafFile))
            : top.getValue();

        Map<String, List<File>> blocks = new LinkedHashMap<>();
        for (File f : allFiles) {
            blocks.computeIfAbsent(topKey(fileImportService.getDisplayName(f)), k -> new ArrayList<>()).add(f);
        }
        List<String> keys = new ArrayList<>(blocks.keySet());
        int i = keys.indexOf(selKey);
        int j = i + direction;
        if (i < 0 || j < 0 || j >= keys.size()) return;
        java.util.Collections.swap(keys, i, j);

        allFiles.clear();
        for (String key : keys) {
            allFiles.addAll(blocks.get(key));
        }
        rebuildTree();
        selectTopLevel(selKey);
    }

    private void selectTopLevel(String key) {
        for (var child : treeRoot.getChildren()) {
            File f = leafItems.get(child);
            String childKey = f != null ? topKey(fileImportService.getDisplayName(f)) : child.getValue();
            if (childKey.equals(key)) {
                fileTree.getSelectionModel().clearSelection();
                fileTree.getSelectionModel().select(child);
                return;
            }
        }
    }

    /* ---------- Plage de pages ---------- */

    private void editRangeForSelection() {
        var item = fileTree.getSelectionModel().getSelectedItem();
        File f = item == null ? null : leafItems.get(item);
        if (f == null || FileImportService.isImage(f)) return;

        Integer pages = pageCounts.get(f);
        var current = ranges.get(f);
        var dialog = new TextInputDialog(current == null ? "" : current.from() + "-" + current.to());
        dialog.setTitle("Plage de pages");
        dialog.setHeaderText(fileImportService.getDisplayName(f)
            + (pages != null && pages > 0 ? " (" + pages + " pages)" : ""));
        dialog.setContentText("Pages à imprimer (ex : 3-10, ou 5) :");
        dialog.initOwner(app.stage());
        app.styleDialog(dialog);
        var input = dialog.showAndWait();
        if (input.isEmpty()) return;

        String text = input.get().trim();
        if (text.isEmpty()) {
            ranges.remove(f);
            rebuildTree();
            return;
        }
        var parsed = parseRange(text, pages);
        if (parsed == null) {
            warning("Plage invalide", "Format attendu : « 3-10 » ou « 5 », dans les limites du document.");
            return;
        }
        ranges.put(f, parsed);
        resumePoints.remove(f);
        rebuildTree();
    }

    private static PrintService.PageRange parseRange(String text, Integer pages) {
        try {
            int from;
            int to;
            int dash = text.indexOf('-');
            if (dash < 0) {
                from = to = Integer.parseInt(text.trim());
            } else {
                from = Integer.parseInt(text.substring(0, dash).trim());
                to = Integer.parseInt(text.substring(dash + 1).trim());
            }
            if (from < 1 || to < from) return null;
            if (pages != null && pages > 0 && from > pages) return null;
            return new PrintService.PageRange(from, to);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /* ---------- Résumé ---------- */

    private int effectivePages(File f) {
        Integer pages = pageCounts.get(f);
        if (pages == null || pages <= 0) return -1;
        var range = ranges.get(f);
        if (range == null) return pages;
        int from = Math.min(range.from(), pages);
        int to = Math.min(range.to(), pages);
        return to - from + 1;
    }

    private List<PrintUnit> buildPrintUnits() {
        boolean group = groupImagesCheck.isSelected();
        List<PrintUnit> units = new ArrayList<>();
        List<File> images = new ArrayList<>();
        int imageUnitIndex = -1;
        for (File f : allFiles) {
            if (group && FileImportService.isImage(f)) {
                if (imageUnitIndex < 0) {
                    imageUnitIndex = units.size();
                    units.add(null);
                }
                images.add(f);
            } else {
                units.add(PrintUnit.single(f));
            }
        }
        if (imageUnitIndex >= 0) {
            units.set(imageUnitIndex, images.size() == 1
                ? PrintUnit.single(images.get(0))
                : PrintUnit.imageGroup(images));
        }
        return units;
    }

    private int unitPages(PrintUnit unit) {
        int total = 0;
        for (File f : unit.allFiles()) {
            int p = effectivePages(f);
            if (p < 0) return -1;
            total += p;
        }
        return total;
    }

    private void updateSummary() {
        if (allFiles.isEmpty()) {
            summaryLabel.setText("");
            return;
        }
        int nup = Integer.parseInt(pagesPerSheetCombo.getSelectionModel().getSelectedItem());
        boolean duplex = duplexSelected();
        int copies = copiesSpinner.getValue();

        long pages = 0;
        long sheets = 0;
        boolean unknown = false;
        for (PrintUnit unit : buildPrintUnits()) {
            int p = unitPages(unit);
            if (p < 0) {
                unknown = true;
                continue;
            }
            pages += p;
            long sides = (p + nup - 1) / nup;
            sheets += duplex ? (sides + 1) / 2 : sides;
        }
        var sb = new StringBuilder();
        sb.append(allFiles.size()).append(allFiles.size() > 1 ? " fichiers" : " fichier");
        sb.append(" — ").append(unknown ? "≥ " : "").append(pages).append(pages > 1 ? " pages" : " page");
        sb.append(", ~").append(sheets * copies).append(sheets * copies > 1 ? " feuilles" : " feuille");
        if (copies > 1) {
            sb.append(" (").append(copies).append(" copies)");
        }
        summaryLabel.setText(sb.toString());
    }

    /* ---------- Print pipeline ---------- */

    private PrintSettings buildPrintSettings() {
        PrintSettings.DuplexMode duplex = switch (rectoVersoCombo.getSelectionModel().getSelectedIndex()) {
            case 1 -> PrintSettings.DuplexMode.LONG_EDGE;
            case 2 -> PrintSettings.DuplexMode.SHORT_EDGE;
            default -> PrintSettings.DuplexMode.RECTO;
        };
        return new PrintSettings(
            batchSizeSpinner.getValue(),
            copiesSpinner.getValue(),
            duplex,
            orientationCombo.getSelectionModel().getSelectedIndex() == 1
                ? PrintSettings.Orientation.LANDSCAPE : PrintSettings.Orientation.PORTRAIT,
            colorCombo.getSelectionModel().getSelectedIndex() == 1
                ? PrintSettings.ColorMode.MONOCHROME : PrintSettings.ColorMode.COLOR,
            Integer.parseInt(pagesPerSheetCombo.getSelectionModel().getSelectedItem())
        );
    }

    /** Nombre total de lots attendus (pour l'estimation de temps) ; -1 par unité inconnue ignorée. */
    private int estimateTotalBatches(List<PrintUnit> units, PrintSettings settings) {
        boolean duplex = settings.duplex() != PrintSettings.DuplexMode.RECTO;
        int total = 0;
        for (PrintUnit unit : units) {
            int p = unitPages(unit);
            if (p < 0) continue;
            int sides = (p + settings.pagesPerSheet() - 1) / settings.pagesPerSheet();
            total += BatchPlanner.plan(0, sides, settings.batchSize(), duplex).size() * settings.copies();
        }
        return total;
    }

    private void startPrinting() {
        if (allFiles.isEmpty()) {
            warning("Attention", "Aucun fichier sélectionné.");
            return;
        }
        PrinterItem item = printerCombo.getSelectionModel().getSelectedItem();
        javax.print.PrintService printer = item != null ? item.service() : null;
        if (printer == null) {
            error("Erreur", "Aucune imprimante disponible.");
            return;
        }
        PrintSettings settings = buildPrintSettings();
        final List<PrintUnit> units = buildPrintUnits();
        final int total = units.size();
        final int totalBatchesEstimate = estimateTotalBatches(units, settings);

        setControlsEnabled(false);
        cancelButton.setDisable(false);
        logArea.clear();
        progressBar.setProgress(0);
        progressLabel.setText("0 / " + total);

        LogService.info("Lancement impression : " + total + " unité(s), imprimante=" + printer.getName());

        List<File> failedFiles = new ArrayList<>();
        long startNanos = System.nanoTime();
        int[] batchesDone = {0};
        var task = new Task<Void>() {
            @Override
            protected Void call() {
                PrintService service = new PrintService();
                for (int i = 0; i < total; i++) {
                    if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                    final int unitIndex = i;
                    PrintUnit unit = units.get(i);
                    File resumeKey = unit.resumeKey();
                    PrintService.ResumePoint resume =
                        resumePoints.getOrDefault(resumeKey, PrintService.ResumePoint.START);
                    java.util.function.Consumer<PrintService.PrintProgress> onProgress = progress -> {
                        String copyPart = progress.totalCopies() > 1
                            ? "Copie " + progress.copy() + "/" + progress.totalCopies() + " — " : "";
                        String msg = String.format("[%s] %sLot %d/%d (pages %d à %d) envoyé",
                            progress.fileName(), copyPart, progress.batchNumber(), progress.totalBatches(),
                            progress.fromPage(), progress.toPage());
                        LogService.info(msg);
                        batchesDone[0]++;
                        String eta = formatEta(startNanos, batchesDone[0], totalBatchesEstimate);
                        float copyFrac = ((progress.copy() - 1) * (float) progress.totalBatches()
                            + progress.batchNumber()) / (progress.totalCopies() * (float) progress.totalBatches());
                        float overall = (unitIndex + copyFrac) / total;
                        Platform.runLater(() -> {
                            appendLog(msg);
                            progressBar.setProgress(overall);
                            progressLabel.setText(unitIndex + " / " + total + eta);
                        });
                    };
                    try {
                        if (unit.file() != null) {
                            service.print(unit.file(), printer, settings, ranges.get(unit.file()),
                                resume, passwords.get(unit.file()), onProgress);
                        } else {
                            service.printImageGroup(unit.images(), unit.label(), printer, settings,
                                resume, onProgress);
                        }
                        resumePoints.remove(resumeKey);
                        int current = i + 1;
                        Platform.runLater(() -> {
                            progressBar.setProgress((double) current / total);
                            progressLabel.setText(current + " / " + total
                                + formatEta(startNanos, batchesDone[0], totalBatchesEstimate));
                        });
                    } catch (PrintService.PartialPrintException ex) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                        PrintService.ResumePoint point = ex.getResumePoint();
                        resumePoints.put(resumeKey, point);
                        String raw = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        String friendly = "Le spooler a interrompu l'envoi après plusieurs tentatives — reprise prévue à la page "
                            + (point.page() + 1)
                            + (settings.copies() > 1 ? " (copie " + point.copy() + ")" : "");
                        Platform.runLater(() -> appendLog("ERREUR [" + unit.label() + "] : " + friendly));
                        LogService.error("Impression partielle pour " + unit.label()
                            + " — reprise page " + (point.page() + 1) + " — " + raw, ex);
                        failedFiles.addAll(unit.allFiles());
                    } catch (java.awt.print.PrinterException ex) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                        String raw = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        String friendly = "Le spooler a interrompu l'envoi après plusieurs tentatives (imprimante occupée ou hors ligne)";
                        Platform.runLater(() -> appendLog("ERREUR [" + unit.label() + "] : " + friendly));
                        LogService.error("Impression échouée pour " + unit.label() + " — " + raw, ex);
                        failedFiles.addAll(unit.allFiles());
                    } catch (Exception ex) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        Platform.runLater(() -> appendLog("ERREUR [" + unit.label() + "] : " + msg));
                        LogService.error("Impression échouée pour " + unit.label(), ex);
                        failedFiles.addAll(unit.allFiles());
                    }
                }
                return null;
            }
        };

        Runnable finish = () -> {
            setControlsEnabled(true);
            cancelButton.setDisable(true);
            currentTask = null;
        };

        task.setOnSucceeded(e -> {
            finish.run();
            appendLog("--- Terminé ---");
            if (!failedFiles.isEmpty()) {
                appendLog("Détails des erreurs dans : " + LogService.getLogFile());
                appendLog(failedFiles.size() + " fichier(s) non imprimé(s) rechargé(s) — cliquer sur Imprimer pour réessayer");
                allFiles.clear();
                allFiles.addAll(failedFiles);
                rebuildTree();
                progressLabel.setText(failedFiles.size() + " en échec");
                LogService.info("Impression terminée avec " + failedFiles.size() + " échec(s), fichiers rechargés dans la liste");
            } else {
                progressLabel.setText("Terminé");
                LogService.info("Impression terminée");
            }
        });
        task.setOnCancelled(e -> {
            finish.run();
            appendLog("--- Annulé ---");
            progressLabel.setText("Annulé");
            LogService.info("Impression annulée par l'utilisateur");
        });
        task.setOnFailed(e -> {
            finish.run();
            Throwable ex = task.getException();
            appendLog("ERREUR : " + (ex == null ? "?" : ex.getMessage()));
            LogService.error("Échec global d'impression", ex);
        });

        currentTask = task;
        var t = new Thread(task, "lammprimante-print");
        t.setDaemon(true);
        t.start();
    }

    private static String formatEta(long startNanos, int batchesDone, int totalBatches) {
        if (batchesDone < 2 || totalBatches <= 0 || batchesDone >= totalBatches) {
            return "";
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        long remainingMs = elapsedMs / batchesDone * (totalBatches - batchesDone);
        long seconds = (remainingMs + 999) / 1000;
        if (seconds < 90) {
            return " — ~" + seconds + "s";
        }
        return " — ~" + ((seconds + 59) / 60) + " min";
    }

    private void cancelPrinting() {
        if (currentTask == null || !currentTask.isRunning()) return;
        cancelButton.setDisable(true);
        appendLog("Annulation demandée — le lot en cours va se terminer…");
        LogService.info("Annulation demandée");
        currentTask.cancel(true);
    }

    /* ---------- Misc ---------- */

    private void appendLog(String message) {
        logArea.appendText(message + "\n");
    }

    private void setControlsEnabled(boolean enabled) {
        printButton.setDisable(!enabled);
        addButton.setDisable(!enabled);
        removeButton.setDisable(!enabled);
        moveUpButton.setDisable(!enabled);
        moveDownButton.setDisable(!enabled);
        printerCombo.setDisable(!enabled);
        batchSizeSpinner.setDisable(!enabled);
        copiesSpinner.setDisable(!enabled);
        rectoVersoCombo.setDisable(!enabled);
        pagesPerSheetCombo.setDisable(!enabled);
        orientationCombo.setDisable(!enabled);
        colorCombo.setDisable(!enabled);
        groupImagesCheck.setDisable(!enabled);
    }

    private void warning(String header, String content) {
        showAlert(Alert.AlertType.WARNING, header, content);
    }

    private void error(String header, String content) {
        showAlert(Alert.AlertType.ERROR, header, content);
    }

    private void showAlert(Alert.AlertType type, String header, String content) {
        var alert = new Alert(type);
        alert.setTitle(header);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.initOwner(app.stage());
        app.styleDialog(alert);
        alert.showAndWait();
    }
}
