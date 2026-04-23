package fr.courel.lammprimante.service;

import fr.courel.lammprimante.App;

import javax.swing.*;
import java.awt.*;
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

    public static void checkForUpdatesAsync() {
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

            SwingUtilities.invokeLater(() -> promptUpdate(release, local));
        } catch (Exception e) {
            LogService.warn("Vérification des mises à jour échouée : " + e.getMessage());
        }
    }

    private static void promptUpdate(ReleaseInfo release, String local) {
        int choice = JOptionPane.showConfirmDialog(null,
                "Nouvelle version disponible : " + release.tag + "\n"
                        + "(version actuelle : " + local + ")\n\n"
                        + "Mettre à jour maintenant ?",
                "Lammprimante - Mise à jour",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE);

        if (choice != JOptionPane.YES_OPTION) return;

        new Thread(() -> downloadAndInstall(release), "update-download").start();
    }

    private static void downloadAndInstall(ReleaseInfo release) {
        JDialog dialog = new JDialog((Frame) null, "Mise à jour", false);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setString("Téléchargement en cours...");
        bar.setStringPainted(true);
        dialog.add(bar);
        dialog.setSize(350, 80);
        dialog.setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));

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
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Échec de la mise à jour :\n" + ex.getMessage(),
                    "Erreur", JOptionPane.ERROR_MESSAGE));
        } finally {
            SwingUtilities.invokeLater(dialog::dispose);
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
