package fr.courel.lammprimante.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileImportService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif", "tiff", "tif");
    private static final int BUFFER_SIZE = 8192;

    /** Maps extracted temp files to their display name (relative path in ZIP or original name). */
    private final Map<File, String> displayNames = new HashMap<>();

    /** Temp directories to clean up on exit. */
    private final List<Path> tempDirs = new ArrayList<>();

    public record ImportResult(List<File> accepted, List<String> rejected, List<String> errors) {}

    /** pages = -1 si inconnu (fichier chiffré sans mot de passe valide, ou illisible). */
    public record FileInfo(int pages, boolean encrypted) {
        public static final FileInfo UNKNOWN = new FileInfo(-1, false);
    }

    /**
     * Compte les pages d'un fichier (PDF ou image, y compris TIFF multi-pages)
     * et détecte les PDF protégés par mot de passe.
     */
    public static FileInfo analyze(File file, String password) {
        if (isImage(file)) {
            return new FileInfo(countImagePages(file), false);
        }
        try (PDDocument doc = password == null ? Loader.loadPDF(file) : Loader.loadPDF(file, password)) {
            return new FileInfo(doc.getNumberOfPages(), false);
        } catch (InvalidPasswordException ex) {
            return new FileInfo(-1, true);
        } catch (IOException ex) {
            LogService.warn("Analyse impossible pour " + file.getName() + " : " + ex.getMessage());
            return FileInfo.UNKNOWN;
        }
    }

    private static int countImagePages(File file) {
        try (var iis = ImageIO.createImageInputStream(file)) {
            var readers = iis == null ? null : ImageIO.getImageReaders(iis);
            if (readers != null && readers.hasNext()) {
                var reader = readers.next();
                try {
                    reader.setInput(iis);
                    return Math.max(1, reader.getNumImages(true));
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException ignored) {
        }
        return 1;
    }

    public String getDisplayName(File file) {
        return displayNames.getOrDefault(file, file.getName());
    }

    public static boolean isSupported(File file) {
        String ext = getExtension(file);
        return ext.equals("pdf") || IMAGE_EXTENSIONS.contains(ext);
    }

    public static boolean isZip(File file) {
        return getExtension(file).equals("zip");
    }

    public static boolean isImage(File file) {
        return IMAGE_EXTENSIONS.contains(getExtension(file));
    }

    public static String getExtension(File file) {
        String name = file.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    /**
     * Import files, reporting each accepted file via onFileAccepted for live progress.
     */
    public ImportResult importFiles(List<File> files, Consumer<String> onFileAccepted) {
        List<File> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (File f : files) {
            if (f.isDirectory()) {
                importDirectory(f, f.getName(), accepted, rejected, errors, onFileAccepted);
            } else if (isZip(f)) {
                extractFromZip(f, accepted, rejected, errors, onFileAccepted);
            } else if (isSupported(f)) {
                accepted.add(f);
                onFileAccepted.accept(getDisplayName(f));
            } else {
                rejected.add(f.getName());
            }
        }

        return new ImportResult(accepted, rejected, errors);
    }

    /** Clean up all temp directories created during extraction. */
    public void cleanup() {
        for (Path dir : tempDirs) {
            deleteRecursive(dir.toFile());
        }
        tempDirs.clear();
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private void importDirectory(File dir, String basePath, List<File> accepted, List<String> rejected,
                                List<String> errors, Consumer<String> onFileAccepted) {
        int countBefore = accepted.size();
        File[] children = dir.listFiles();
        if (children == null) {
            errors.add("Impossible de lire le dossier " + dir.getName());
            return;
        }

        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory()) {
                importDirectory(child, basePath + "/" + child.getName(), accepted, rejected, errors, onFileAccepted);
            } else if (isZip(child)) {
                extractFromZip(child, accepted, rejected, errors, onFileAccepted);
            } else if (isSupported(child)) {
                accepted.add(child);
                String displayName = basePath + "/" + child.getName();
                displayNames.put(child, displayName);
                onFileAccepted.accept(displayName);
            } else {
                rejected.add(basePath + "/" + child.getName());
            }
        }

        if (accepted.size() == countBefore) {
            errors.add("Aucun fichier supporté trouvé dans le dossier " + basePath);
        }
    }

    private void extractFromZip(File zipFile, List<File> accepted, List<String> rejected,
                                List<String> errors, Consumer<String> onFileAccepted) {
        int countBefore = accepted.size();
        try {
            Path tempDir = Files.createTempDirectory("lammprimante-");
            tempDirs.add(tempDir);

            try (var fis = new BufferedInputStream(new FileInputStream(zipFile), BUFFER_SIZE);
                 var zis = new ZipInputStream(fis, Charset.forName("CP437"))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String entryName = entry.getName();
                    if (entryName.contains("__MACOSX") || entryName.startsWith(".")) continue;

                    // Preserve directory structure to avoid name collisions
                    Path relativePath = Path.of(entryName);
                    Path extractPath = tempDir.resolve(relativePath).normalize();
                    if (!extractPath.startsWith(tempDir)) {
                        // Zip Slip : entrée avec des ".." qui sortirait du répertoire temporaire
                        rejected.add(entryName + " (chemin invalide, dans " + zipFile.getName() + ")");
                        continue;
                    }
                    Files.createDirectories(extractPath.getParent());

                    try (var bos = new BufferedOutputStream(new FileOutputStream(extractPath.toFile()), BUFFER_SIZE)) {
                        zis.transferTo(bos);
                    }

                    if (isSupported(extractPath.toFile())) {
                        accepted.add(extractPath.toFile());
                        String displayName = zipFile.getName() + "/" + entryName;
                        displayNames.put(extractPath.toFile(), displayName);
                        onFileAccepted.accept(displayName);
                    } else {
                        rejected.add(entryName + " (dans " + zipFile.getName() + ")");
                        extractPath.toFile().delete();
                    }
                }
            }

            if (accepted.size() == countBefore) {
                errors.add("Aucun fichier supporté trouvé dans " + zipFile.getName());
            }
        } catch (IOException ex) {
            errors.add(zipFile.getName() + " : " + ex.getMessage());
        }
    }
}
