package fr.courel.lammprimante.service;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long MAX_SIZE = 5 * 1024 * 1024; // 5 Mo
    private static final Path LOG_FILE = resolveLogFile();

    private static Path resolveLogFile() {
        // À côté du JAR, ou dans le répertoire utilisateur en fallback
        try {
            Path jarDir = Path.of(LogService.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            if (jarDir != null && Files.isWritable(jarDir)) {
                return jarDir.resolve("lammprimante.log");
            }
        } catch (Exception ignored) {}
        return Path.of(System.getProperty("user.home"), "lammprimante.log");
    }

    public static Path getLogFile() {
        return LOG_FILE;
    }

    public static void info(String message) {
        write("INFO", message);
    }

    public static void warn(String message) {
        write("WARN", message);
    }

    public static void error(String message) {
        write("ERROR", message);
    }

    public static void error(String message, Throwable throwable) {
        StringWriter sw = new StringWriter();
        sw.write(message + "\n");
        throwable.printStackTrace(new PrintWriter(sw));
        write("ERROR", sw.toString());
    }

    private static synchronized void write(String level, String message) {
        try {
            rotateIfNeeded();
            String timestamp = LocalDateTime.now().format(FORMATTER);
            String line = "[" + timestamp + "] " + level + " - " + message + "\n";
            Files.writeString(LOG_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private static void rotateIfNeeded() throws IOException {
        if (Files.exists(LOG_FILE) && Files.size(LOG_FILE) > MAX_SIZE) {
            Path backup = LOG_FILE.resolveSibling("lammprimante.log.old");
            Files.deleteIfExists(backup);
            Files.move(LOG_FILE, backup);
        }
    }
}
