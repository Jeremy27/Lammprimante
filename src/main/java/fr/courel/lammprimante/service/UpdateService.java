package fr.courel.lammprimante.service;

import fr.courel.lammprimante.App;
import fr.courel.lammui.fx.component.LammProgressBarFx;
import fr.courel.lammui.fx.theme.LammThemeFx;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

public class UpdateService {

    private static final String GITHUB_API = "https://api.github.com/repos/Jeremy27/Lammprimante/releases/latest";
    private static final int TIMEOUT = 5000;

    private static Stage parentStage;

    public static void checkForUpdatesAsync(Stage parent) {
        parentStage = parent;
        if (!isWindows()) return;
        Thread t = new Thread(UpdateService::checkForUpdates, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void checkForUpdates() {
        try {
            ReleaseInfo release = fetchLatestRelease();
            if (release == null) return;

            String local = App.getVersion();
            if ("inconnue".equals(local) || !isNewer(release.tag, local)) return;

            Platform.runLater(() -> promptUpdate(release, local));
        } catch (Exception e) {
            LogService.warn("Vérification des mises à jour échouée : " + e.getMessage());
        }
    }

    private static void promptUpdate(ReleaseInfo release, String local) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Mise à jour disponible");
        alert.setHeaderText("Mise à jour disponible");
        alert.setContentText("Une nouvelle version " + release.tag + " est disponible "
            + "(version actuelle : " + local + ").\n\n"
            + "Voulez-vous l'installer maintenant ?");
        alert.initOwner(parentStage);
        if (parentStage != null && parentStage.getUserData() instanceof App app) {
            app.styleDialog(alert);
        } else {
            tryStyleDialogFromStage(alert);
        }
        var result = alert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        new Thread(() -> downloadAndInstall(release), "update-download").start();
    }

    private static void downloadAndInstall(ReleaseInfo release) {
        Stage[] dialogHolder = new Stage[1];
        Platform.runLater(() -> {
            dialogHolder[0] = buildDownloadStage(release);
            dialogHolder[0].show();
        });

        try {
            Path msi = Files.createTempFile("lammprimante-", ".msi");
            HttpURLConnection conn = (HttpURLConnection) URI.create(release.msiDownloadUrl).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(60000);

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(msi))) {
                in.transferTo(out);
            }

            LogService.info("Mise à jour téléchargée : " + msi);

            // Laisser 3s au process courant pour libérer les fichiers avant que msiexec ne prenne la main
            String cmdLine = "timeout /t 3 /nobreak >nul & msiexec /i \"" + msi + "\" /passive /norestart";
            new ProcessBuilder("cmd", "/c", cmdLine).start();
            System.exit(0);
        } catch (Exception ex) {
            LogService.error("Échec de la mise à jour", ex);
            Platform.runLater(() -> {
                if (dialogHolder[0] != null) dialogHolder[0].close();
                var alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Mise à jour impossible");
                alert.setHeaderText("Mise à jour impossible");
                alert.setContentText("Le téléchargement a échoué :\n" + ex.getMessage());
                alert.initOwner(parentStage);
                tryStyleDialogFromStage(alert);
                alert.showAndWait();
            });
        }
    }

    private static Stage buildDownloadStage(ReleaseInfo release) {
        var title = new Label("Mise à jour en cours");
        title.getStyleClass().add("lamm-title");
        var subtitle = new Label("Téléchargement de la version " + release.tag + "…");
        subtitle.getStyleClass().add("lamm-subtitle");
        var progress = new LammProgressBarFx();
        progress.setProgress(-1);
        progress.setMaxWidth(Double.MAX_VALUE);

        var content = new VBox(12, title, subtitle, progress);
        content.setPadding(new Insets(24, 28, 24, 28));

        var stage = new Stage();
        stage.initOwner(parentStage);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("Mise à jour");
        var scene = new Scene(content, 420, 160);
        LammThemeFx.install(scene);
        if (parentStage != null && parentStage.getScene() != null) {
            scene.getStylesheets().setAll(parentStage.getScene().getStylesheets());
            for (var cls : parentStage.getScene().getRoot().getStyleClass()) {
                if ("dark".equals(cls) || cls.startsWith("accent-")) {
                    if (!scene.getRoot().getStyleClass().contains(cls)) {
                        scene.getRoot().getStyleClass().add(cls);
                    }
                }
            }
        }
        stage.setScene(scene);
        return stage;
    }

    private static void tryStyleDialogFromStage(javafx.scene.control.Dialog<?> dialog) {
        if (parentStage == null || parentStage.getScene() == null) return;
        var pane = dialog.getDialogPane();
        pane.setGraphic(null);
        pane.getStylesheets().setAll(parentStage.getScene().getStylesheets());
        for (var cls : parentStage.getScene().getRoot().getStyleClass()) {
            if ("dark".equals(cls) || cls.startsWith("accent-")) {
                if (!pane.getStyleClass().contains(cls)) {
                    pane.getStyleClass().add(cls);
                }
            }
        }
    }

    private static ReleaseInfo fetchLatestRelease() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(GITHUB_API).toURL().openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);

        if (conn.getResponseCode() != 200) return null;

        String json = new String(conn.getInputStream().readAllBytes());
        String tag = extractJsonString(json, "tag_name");
        String msiUrl = findAssetUrl(json, ".msi");

        if (tag == null || msiUrl == null) return null;
        return new ReleaseInfo(tag, msiUrl);
    }

    static boolean isNewer(String remote, String local) {
        if (remote == null || local == null) return false;
        remote = remote.startsWith("v") ? remote.substring(1) : remote;
        local = local.startsWith("v") ? local.substring(1) : local;

        String[] r = remote.split("\\.");
        String[] l = local.split("\\.");
        int len = Math.max(r.length, l.length);
        for (int i = 0; i < len; i++) {
            int rv = i < r.length ? parse(r[i]) : 0;
            int lv = i < l.length ? parse(l[i]) : 0;
            if (rv > lv) return true;
            if (rv < lv) return false;
        }
        return false;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        idx = json.indexOf("\"", idx + pattern.length() + 1);
        if (idx < 0) return null;
        int end = json.indexOf("\"", idx + 1);
        if (end < 0) return null;
        return json.substring(idx + 1, end);
    }

    private static String findAssetUrl(String json, String extension) {
        String key = "browser_download_url";
        int searchFrom = 0;
        while (true) {
            String pattern = "\"" + key + "\"";
            int idx = json.indexOf(pattern, searchFrom);
            if (idx < 0) return null;
            idx = json.indexOf("\"", idx + pattern.length() + 1);
            if (idx < 0) return null;
            int end = json.indexOf("\"", idx + 1);
            if (end < 0) return null;
            String url = json.substring(idx + 1, end);
            if (url.endsWith(extension)) return url;
            searchFrom = end + 1;
        }
    }

    private record ReleaseInfo(String tag, String msiDownloadUrl) {}
}
