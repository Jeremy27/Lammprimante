package fr.courel.lammprimante.view;

import fr.courel.lammprimante.App;
import fr.courel.lammprimante.config.PreferencesManager;
import fr.courel.lammprimante.model.PrintSettings;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
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
    private final Map<File, Integer> resumePages = new HashMap<>();

    private final TreeItem<String> treeRoot = new TreeItem<>("Fichiers");
    private final LammTreeFx<String> fileTree = new LammTreeFx<>(treeRoot);

    private final ComboBox<PrinterItem> printerCombo;
    private final LammSpinnerFx batchSizeSpinner;
    private final LammSpinnerFx copiesSpinner;
    private final ComboBox<String> rectoVersoCombo;
    private final ComboBox<String> pagesPerSheetCombo;
    private final ComboBox<String> orientationCombo;
    private final ComboBox<String> colorCombo;

    private final TextArea logArea = new TextArea();
    private final LammButtonFx printButton = LammButtonFx.primary("Imprimer");
    private final LammButtonFx cancelButton = new LammButtonFx("Annuler");
    private final LammButtonFx addButton = new LammButtonFx("Ajouter des fichiers…");
    private final LammButtonFx removeButton = new LammButtonFx("Retirer");
    private final LammProgressBarFx progressBar = new LammProgressBarFx(0);
    private final Label progressLabel = new Label();

    private Task<Void> currentTask;

    private record PrinterItem(javax.print.PrintService service) {
        @Override
        public String toString() {
            return service != null ? service.getName() : "—";
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

        wirePreferenceListeners();

        treeRoot.setExpanded(true);
        fileTree.setShowRoot(false);
        fileTree.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);

        logArea.setEditable(false);
        logArea.setPrefRowCount(6);

        progressBar.setMaxWidth(Double.MAX_VALUE);
        cancelButton.setDisable(true);

        addButton.setOnAction(e -> addFiles());
        removeButton.setOnAction(e -> removeSelected());
        printButton.setOnAction(e -> startPrinting());
        cancelButton.setOnAction(e -> cancelPrinting());

        installDragAndDrop();

        setSpacing(12);
        setPadding(new Insets(16, 20, 20, 20));
        getChildren().addAll(buildSettingsCard(), buildFilesCard(), buildBottom());
        VBox.setVgrow(getChildren().get(1), Priority.ALWAYS);
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

        card.getChildren().add(grid);
        return card;
    }

    private LammCardFx buildFilesCard() {
        var card = new LammCardFx("Fichiers (glisser-déposer PDF, images, ZIP ou dossiers ici)");
        var scroll = new ScrollPane(fileTree);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(180);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        var buttons = new HBox(8, addButton, removeButton);
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
        progressLabel.setMinWidth(80);

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
            if (nv != null) PreferencesManager.setBatchSize(nv);
        });
        rectoVersoCombo.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) ->
            PreferencesManager.setDuplex(nv.intValue()));
        orientationCombo.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) ->
            PreferencesManager.setOrientation(nv.intValue()));
        colorCombo.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) ->
            PreferencesManager.setColor(nv.intValue()));
    }

    private void installDragAndDrop() {
        fileTree.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        fileTree.setOnDragDropped(e -> {
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

        Set<String> removedDisplayNames = new HashSet<>();
        for (var item : new ArrayList<>(selectedItems)) {
            removedDisplayNames.add(buildPath(item));
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

    private static String buildPath(TreeItem<String> item) {
        var parts = new ArrayList<String>();
        var cur = item;
        while (cur != null && cur.getParent() != null) {
            parts.add(0, cur.getValue());
            cur = cur.getParent();
        }
        return String.join("/", parts);
    }

    private void importFiles(List<File> files) {
        LogService.info("Import de " + files.size() + " élément(s)");
        appendLog("Import en cours...");
        setControlsEnabled(false);
        progressBar.setProgress(-1);
        progressLabel.setText("Chargement…");

        var task = new Task<FileImportService.ImportResult>() {
            @Override
            protected FileImportService.ImportResult call() {
                return fileImportService.importFiles(files, displayName -> {});
            }
        };
        task.setOnSucceeded(e -> {
            progressBar.setProgress(0);
            progressLabel.setText("");
            FileImportService.ImportResult result = task.getValue();
            for (File f : result.accepted()) {
                if (!allFiles.contains(f)) allFiles.add(f);
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

    private void rebuildTree() {
        treeRoot.getChildren().clear();
        Map<String, TreeItem<String>> folderNodes = new LinkedHashMap<>();
        for (File f : allFiles) {
            String displayName = fileImportService.getDisplayName(f);
            String[] parts = displayName.split("/");
            if (parts.length == 1) {
                treeRoot.getChildren().add(new TreeItem<>(parts[0]));
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
                parent.getChildren().add(new TreeItem<>(parts[parts.length - 1]));
            }
        }
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

        final List<File> files = new ArrayList<>(allFiles);
        final int total = files.size();

        setControlsEnabled(false);
        cancelButton.setDisable(false);
        logArea.clear();
        progressBar.setProgress(0);
        progressLabel.setText("0 / " + total);

        LogService.info("Lancement impression : " + total + " fichier(s), imprimante=" + printer.getName());

        List<File> failedFiles = new ArrayList<>();
        var task = new Task<Void>() {
            @Override
            protected Void call() {
                PrintService service = new PrintService();
                for (int i = 0; i < total; i++) {
                    if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                    final int fileIndex = i;
                    File file = files.get(i);
                    int startPage = resumePages.getOrDefault(file, 0);
                    try {
                        service.print(file, printer, settings, startPage, progress -> {
                            String msg = String.format("[%s] Lot %d/%d (pages %d à %d) envoyé",
                                progress.fileName(), progress.batchNumber(), progress.totalBatches(),
                                progress.fromPage(), progress.toPage());
                            Platform.runLater(() -> appendLog(msg));
                            LogService.info(msg);
                            float fileFrac = progress.totalBatches() > 0
                                ? (float) progress.batchNumber() / progress.totalBatches() : 0f;
                            float overall = (fileIndex + fileFrac) / total;
                            Platform.runLater(() -> progressBar.setProgress(overall));
                        });
                        resumePages.remove(file);
                        int current = i + 1;
                        Platform.runLater(() -> {
                            progressBar.setProgress((double) current / total);
                            progressLabel.setText(current + " / " + total);
                        });
                    } catch (PrintService.PartialPrintException ex) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                        int resume = ex.getResumePage();
                        resumePages.put(file, resume);
                        String raw = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        String friendly = "Le spooler a interrompu l'envoi après plusieurs tentatives — reprise prévue à la page " + (resume + 1);
                        Platform.runLater(() -> appendLog("ERREUR [" + file.getName() + "] : " + friendly));
                        LogService.error("Impression partielle pour " + file.getName() + " — reprise page " + (resume + 1) + " — " + raw, ex);
                        failedFiles.add(file);
                    } catch (java.awt.print.PrinterException ex) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                        String raw = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        String friendly = "Le spooler a interrompu l'envoi après plusieurs tentatives (imprimante occupée ou hors ligne)";
                        Platform.runLater(() -> appendLog("ERREUR [" + file.getName() + "] : " + friendly));
                        LogService.error("Impression échouée pour " + file.getName() + " — " + raw, ex);
                        failedFiles.add(file);
                    } catch (Exception ex) {
                        if (isCancelled() || Thread.currentThread().isInterrupted()) return null;
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
                        Platform.runLater(() -> appendLog("ERREUR [" + file.getName() + "] : " + msg));
                        LogService.error("Impression échouée pour " + file.getName(), ex);
                        failedFiles.add(file);
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
        printerCombo.setDisable(!enabled);
        batchSizeSpinner.setDisable(!enabled);
        copiesSpinner.setDisable(!enabled);
        rectoVersoCombo.setDisable(!enabled);
        pagesPerSheetCombo.setDisable(!enabled);
        orientationCombo.setDisable(!enabled);
        colorCombo.setDisable(!enabled);
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
