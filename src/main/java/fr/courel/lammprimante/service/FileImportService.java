package fr.courel.lammprimante.service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileImportService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif", "tiff", "tif");

    public record ImportResult(List<File> accepted, List<String> rejected) {}

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

        for (File f : files) {
            if (isZip(f)) {
                extractFromZip(f, accepted, rejected);
            } else if (isSupported(f)) {
                accepted.add(f);
            } else {
                rejected.add(f.getName());
            }
        }

        return new ImportResult(accepted, rejected);
    }

    private void extractFromZip(File zipFile, List<File> accepted, List<String> rejected) {
        try {
            Path tempDir = Files.createTempDirectory("lammprimante-");
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

                    if (isSupported(extracted)) {
                        accepted.add(extracted);
                    } else {
                        rejected.add(fileName + " (dans " + zipFile.getName() + ")");
                        extracted.delete();
                    }
                }
            }
        } catch (IOException ex) {
            rejected.add(zipFile.getName() + " (erreur : " + ex.getMessage() + ")");
        }
    }
}
