package fr.courel.lammprimante.service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileImportService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif", "tiff", "tif");

    /** Maps extracted temp files to their display name (relative path in ZIP or original name). */
    private final Map<File, String> displayNames = new HashMap<>();

    public record ImportResult(List<File> accepted, List<String> rejected, List<String> errors) {}

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

    public ImportResult importFiles(List<File> files) {
        List<File> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (File f : files) {
            if (isZip(f)) {
                extractFromZip(f, accepted, rejected, errors);
            } else if (isSupported(f)) {
                accepted.add(f);
            } else {
                rejected.add(f.getName());
            }
        }

        return new ImportResult(accepted, rejected, errors);
    }

    private void extractFromZip(File zipFile, List<File> accepted, List<String> rejected, List<String> errors) {
        int countBefore = accepted.size();
        try {
            Path tempDir = Files.createTempDirectory("lammprimante-");
            tempDir.toFile().deleteOnExit();

            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String entryName = entry.getName();
                    if (entryName.contains("__MACOSX") || entryName.startsWith(".")) continue;

                    // Preserve directory structure to avoid name collisions
                    Path relativePath = Path.of(entryName);
                    Path extractPath = tempDir.resolve(relativePath);
                    Files.createDirectories(extractPath.getParent());
                    extractPath.toFile().deleteOnExit();

                    try (FileOutputStream fos = new FileOutputStream(extractPath.toFile())) {
                        zis.transferTo(fos);
                    }

                    if (isSupported(extractPath.toFile())) {
                        accepted.add(extractPath.toFile());
                        // Store display name as "archive.zip/path/to/file.pdf"
                        displayNames.put(extractPath.toFile(), zipFile.getName() + "/" + entryName);
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
