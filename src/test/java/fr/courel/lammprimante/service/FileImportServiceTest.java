package fr.courel.lammprimante.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FileImportServiceTest {

    @TempDir
    Path tempDir;

    private final FileImportService service = new FileImportService();

    @AfterEach
    void cleanup() {
        service.cleanup();
    }

    @Test
    void zipSlipRejete() throws Exception {
        File zip = tempDir.resolve("evil.zip").toFile();
        try (var zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("sous/../../../../evil.pdf"));
            zos.write("fake".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("ok.pdf"));
            zos.write("fake".getBytes());
            zos.closeEntry();
        }

        var result = service.importFiles(List.of(zip), name -> {});

        assertEquals(1, result.accepted().size(), "seule l'entrée saine doit passer");
        assertTrue(result.accepted().get(0).getName().endsWith("ok.pdf"));
        assertTrue(result.rejected().stream().anyMatch(r -> r.contains("chemin invalide")),
            "l'entrée avec ../ doit être rejetée : " + result.rejected());
        assertFalse(new File(tempDir.toFile().getParentFile().getParentFile(), "evil.pdf").exists());
    }

    @Test
    void zipNormalExtrait() throws Exception {
        File zip = tempDir.resolve("dossier.zip").toFile();
        try (var zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("sous/piece1.pdf"));
            zos.write("fake".getBytes());
            zos.closeEntry();
        }

        var result = service.importFiles(List.of(zip), name -> {});

        assertEquals(1, result.accepted().size());
        assertEquals("dossier.zip/sous/piece1.pdf", service.getDisplayName(result.accepted().get(0)));
    }
}
