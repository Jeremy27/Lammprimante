package fr.courel.lammprimante.launcher;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class Launcher {

    private static final String GITHUB_API = "https://api.github.com/repos/Jeremy27/Lammprimante/releases/latest";
    private static final String APP_EXE = "Lammprimante.exe";
    private static final int TIMEOUT = 5000;

    public static void main(String[] args) {
        Path appDir = resolveAppDir();
        Path appExe = appDir.resolve(APP_EXE);

        // Read current local version
        String localVersion = readLocalVersion(appDir);

        // Check for updates (silently fail if no network)
        try {
            ReleaseInfo release = checkLatestRelease();
            if (release != null && localVersion != null && isNewer(release.tag, localVersion)) {
                int choice = JOptionPane.showConfirmDialog(null,
                        "Nouvelle version disponible : " + release.tag + "\n"
                                + "(version actuelle : " + localVersion + ")\n\n"
                                + "Mettre à jour maintenant ?",
                        "Lammprimante - Mise à jour",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.INFORMATION_MESSAGE);

                if (choice == JOptionPane.YES_OPTION) {
                    downloadUpdate(release, appExe);
                }
            }
        } catch (Exception ignored) {
            // No network or API error — launch normally
        }

        // Launch the app
        try {
            new ProcessBuilder(appExe.toString()).directory(appDir.toFile()).start();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null,
                    "Impossible de lancer Lammprimante :\n" + ex.getMessage(),
                    "Erreur", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static Path resolveAppDir() {
        try {
            Path jarPath = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jarPath.getParent();
        } catch (Exception e) {
            return Path.of(".");
        }
    }

    private static String readLocalVersion(Path appDir) {
        // Read version from the main JAR's version.txt
        Path versionFile = appDir.resolve("version.txt");
        try {
            return Files.readString(versionFile).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static ReleaseInfo checkLatestRelease() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(GITHUB_API).toURL().openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);

        if (conn.getResponseCode() != 200) return null;

        String json = new String(conn.getInputStream().readAllBytes());
        String tag = extractJsonString(json, "tag_name");
        String exeUrl = findExeAssetUrl(json);

        if (tag == null || exeUrl == null) return null;
        return new ReleaseInfo(tag, exeUrl);
    }

    private static void downloadUpdate(ReleaseInfo release, Path target) throws Exception {
        String url = release.exeDownloadUrl;
        JDialog dialog = new JDialog((Frame) null, "Mise à jour", false);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setString("Téléchargement en cours...");
        bar.setStringPainted(true);
        dialog.add(bar);
        dialog.setSize(350, 80);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(30000);

            Path temp = target.resolveSibling(APP_EXE + ".update");
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                in.transferTo(out);
            }

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

            // Update local version.txt from release tag
            Path versionFile = target.resolveSibling("version.txt");
            String newVersion = release.tag.startsWith("v") ? release.tag.substring(1) : release.tag;
            Files.writeString(versionFile, newVersion);
        } finally {
            dialog.dispose();
        }
    }

    /**
     * Compare version strings (e.g. "1.2.0" vs "1.1.0").
     * Strips leading 'v' if present.
     */
    static boolean isNewer(String remote, String local) {
        remote = remote.startsWith("v") ? remote.substring(1) : remote;
        local = local.startsWith("v") ? local.substring(1) : local;

        String[] r = remote.split("\\.");
        String[] l = local.split("\\.");
        int len = Math.max(r.length, l.length);
        for (int i = 0; i < len; i++) {
            int rv = i < r.length ? Integer.parseInt(r[i]) : 0;
            int lv = i < l.length ? Integer.parseInt(l[i]) : 0;
            if (rv > lv) return true;
            if (rv < lv) return false;
        }
        return false;
    }

    /** Minimal JSON string extraction — no dependency needed. */
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

    /** Find the download URL for the .exe asset in the release. */
    private static String findExeAssetUrl(String json) {
        // Look for browser_download_url ending with .exe
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
            if (url.endsWith(".exe")) return url;
            searchFrom = end + 1;
        }
    }

    private record ReleaseInfo(String tag, String exeDownloadUrl) {}
}
