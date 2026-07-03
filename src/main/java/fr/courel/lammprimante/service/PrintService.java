package fr.courel.lammprimante.service;

import fr.courel.lammprimante.model.PrintSettings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.printing.PDFPageable;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.print.attribute.HashPrintRequestAttributeSet;
import java.awt.image.BufferedImage;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public class PrintService {

    private static final long[] RETRY_DELAYS_MS = {2_000L, 10_000L, 30_000L};

    public record PrintProgress(String fileName, int copy, int totalCopies,
                                int batchNumber, int totalBatches, int fromPage, int toPage) {}

    /** Plage 1-based inclusive demandée par l'utilisateur ; null = tout le document. */
    public record PageRange(int from, int to) {}

    /** Point de reprise après échec spooler : copie 1-based, page 0-based dans le document imprimé. */
    public record ResumePoint(int copy, int page) {
        public static final ResumePoint START = new ResumePoint(1, 0);
    }

    public static class PartialPrintException extends Exception {
        private final ResumePoint resumePoint;
        public PartialPrintException(ResumePoint resumePoint, Throwable cause) {
            super(cause.getMessage(), cause);
            this.resumePoint = resumePoint;
        }
        public ResumePoint getResumePoint() { return resumePoint; }
    }

    public void print(File file, javax.print.PrintService printer, PrintSettings settings,
                      PageRange range, ResumePoint resume, String password,
                      Consumer<PrintProgress> onProgress) throws Exception {
        long fileStart = System.nanoTime();
        PDDocument loaded;
        if (FileImportService.isImage(file)) {
            loaded = imagesToPdf(List.of(file));
        } else {
            try {
                loaded = password == null ? Loader.loadPDF(file) : Loader.loadPDF(file, password);
            } catch (InvalidPasswordException ex) {
                throw new Exception("Le PDF est protégé par un mot de passe"
                        + (password == null ? "" : " (mot de passe incorrect)"));
            }
        }
        try (loaded) {
            LogService.info(String.format("[%s] chargement=%dms (taille=%dKo)",
                    file.getName(), (System.nanoTime() - fileStart) / 1_000_000, file.length() / 1024));
            printDocument(file.getName(), loaded, printer, settings, range, resume, onProgress, fileStart);
        }
    }

    /** Imprime un groupe d'images fusionnées en un seul document batché (utile en recto/verso). */
    public void printImageGroup(List<File> images, String displayName,
                                javax.print.PrintService printer, PrintSettings settings,
                                ResumePoint resume, Consumer<PrintProgress> onProgress) throws Exception {
        long start = System.nanoTime();
        try (PDDocument doc = imagesToPdf(images)) {
            LogService.info(String.format("[%s] fusion de %d image(s) en %d page(s), %dms",
                    displayName, images.size(), doc.getNumberOfPages(),
                    (System.nanoTime() - start) / 1_000_000));
            printDocument(displayName, doc, printer, settings, null, resume, onProgress, start);
        }
    }

    private void printDocument(String displayName, PDDocument loaded,
                               javax.print.PrintService printer, PrintSettings settings,
                               PageRange range, ResumePoint resume,
                               Consumer<PrintProgress> onProgress, long fileStart) throws Exception {
        PDDocument document = loaded;
        PDDocument extracted = null;
        PDDocument composed = null;
        try {
            if (range != null) {
                int last = loaded.getNumberOfPages();
                int from = Math.max(1, range.from());
                int to = Math.min(Math.max(from, range.to()), last);
                if (from > last) {
                    throw new Exception("Plage de pages " + range.from() + "-" + range.to()
                            + " hors du document (" + last + " page(s))");
                }
                if (from > 1 || to < last) {
                    extracted = extractPages(loaded, from, to);
                    document = extracted;
                    LogService.info(String.format("[%s] plage %d-%d extraite", displayName, from, to));
                }
            }

            if (settings.pagesPerSheet() > 1) {
                long nupStart = System.nanoTime();
                composed = new NUpService().compose(document, settings.pagesPerSheet());
                document = composed;
                LogService.info(String.format("[%s] N-up compose=%dms",
                        displayName, (System.nanoTime() - nupStart) / 1_000_000));
            }

            int totalPages = document.getNumberOfPages();
            boolean duplexOn = settings.duplex() != PrintSettings.DuplexMode.RECTO;
            int totalCopies = Math.max(1, settings.copies());
            HashPrintRequestAttributeSet attrs = settings.toAttributes();

            long totalSplitMs = 0;
            long totalPrintMs = 0;

            for (int copy = Math.max(1, resume.copy()); copy <= totalCopies; copy++) {
                int startPage = copy == resume.copy() ? Math.min(Math.max(0, resume.page()), totalPages) : 0;
                List<BatchPlanner.Batch> batches =
                        BatchPlanner.plan(startPage, totalPages, settings.batchSize(), duplexOn);

                for (int b = 0; b < batches.size(); b++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Impression annulée");
                    }
                    BatchPlanner.Batch batch = batches.get(b);
                    boolean wholeDoc = batch.fromPage() == 0 && batch.toPage() == totalPages;

                    // Cas wholeDoc : on imprime `document` directement (économise une
                    // copie complète via Splitter). Sinon : sous-document avec uniquement
                    // les pages du lot (PageRanges via Pageable est ignoré par certains drivers).
                    long splitStart = System.nanoTime();
                    PDDocument batchDoc;
                    List<PDDocument> parts = null;
                    if (wholeDoc) {
                        batchDoc = document;
                    } else {
                        Splitter splitter = new Splitter();
                        splitter.setStartPage(batch.fromPage() + 1);
                        splitter.setEndPage(batch.toPage());
                        splitter.setSplitAtPage(batch.size());
                        parts = splitter.split(document);
                        batchDoc = parts.get(0);
                    }
                    long splitMs = (System.nanoTime() - splitStart) / 1_000_000;
                    totalSplitMs += splitMs;
                    try {
                        String jobName = displayName
                                + (totalCopies > 1 ? " - copie " + copy + "/" + totalCopies : "")
                                + " - lot " + (b + 1) + "/" + batches.size();
                        long printStart = System.nanoTime();
                        try {
                            printWithRetry(printer, batchDoc, jobName, attrs, displayName, b + 1, batches.size());
                        } catch (PrinterException ex) {
                            throw new PartialPrintException(new ResumePoint(copy, batch.fromPage()), ex);
                        }
                        long printMs = (System.nanoTime() - printStart) / 1_000_000;
                        totalPrintMs += printMs;
                        LogService.info(String.format("[%s]%s Lot %d/%d : split=%dms%s, print=%dms",
                                displayName, totalCopies > 1 ? " Copie " + copy + "/" + totalCopies : "",
                                b + 1, batches.size(), splitMs, wholeDoc ? " (skip)" : "", printMs));
                    } finally {
                        if (parts != null) {
                            for (PDDocument part : parts) {
                                part.close();
                            }
                        }
                    }

                    onProgress.accept(new PrintProgress(displayName, copy, totalCopies,
                            b + 1, batches.size(), batch.fromPage() + 1, batch.toPage()));
                }
            }

            LogService.info(String.format("[%s] récap : splits=%dms, prints=%dms, total=%dms",
                    displayName, totalSplitMs, totalPrintMs, (System.nanoTime() - fileStart) / 1_000_000));
        } finally {
            if (composed != null) {
                composed.close();
            }
            if (extracted != null) {
                extracted.close();
            }
        }
    }

    private static PDDocument extractPages(PDDocument source, int fromInclusive1Based, int toInclusive1Based)
            throws IOException {
        Splitter splitter = new Splitter();
        splitter.setStartPage(fromInclusive1Based);
        splitter.setEndPage(toInclusive1Based);
        splitter.setSplitAtPage(toInclusive1Based - fromInclusive1Based + 1);
        List<PDDocument> parts = splitter.split(source);
        PDDocument result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            parts.get(i).close();
        }
        return result;
    }

    private void printWithRetry(javax.print.PrintService printer, PDDocument document, String jobName,
                                HashPrintRequestAttributeSet attrs, String fileName,
                                int batchNumber, int totalBatches) throws PrinterException {
        PrinterException lastError = null;
        int totalAttempts = RETRY_DELAYS_MS.length + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(printer);
            job.setPageable(new PDFPageable(document));
            job.setJobName(jobName);
            try {
                job.print(attrs);
                if (attempt > 1) {
                    LogService.info("[" + fileName + "] Lot " + batchNumber + "/" + totalBatches
                            + " : envoi réussi à la tentative " + attempt);
                }
                return;
            } catch (PrinterException ex) {
                lastError = ex;
                if (attempt < totalAttempts) {
                    long delay = RETRY_DELAYS_MS[attempt - 1];
                    LogService.warn("[" + fileName + "] Lot " + batchNumber + "/" + totalBatches
                            + " : échec spooler (tentative " + attempt + "/" + totalAttempts
                            + "), nouvelle tentative dans " + (delay / 1000) + "s");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new PrinterException("Impression interrompue");
                    }
                }
            }
        }
        throw lastError;
    }

    /* ---------- Images ---------- */

    /**
     * Convertit une liste d'images en un document PDF (une page A4 par image,
     * toutes les pages des TIFF multi-pages). Les images passent ainsi par le
     * même pipeline que les PDF : lots, retry spooler, duplex, N-up.
     */
    private static PDDocument imagesToPdf(List<File> imageFiles) throws Exception {
        PDDocument doc = new PDDocument();
        try {
            for (File imageFile : imageFiles) {
                addImageFile(doc, imageFile);
            }
            if (doc.getNumberOfPages() == 0) {
                throw new Exception("Aucune image lisible");
            }
            return doc;
        } catch (Exception ex) {
            doc.close();
            throw ex;
        }
    }

    private static void addImageFile(PDDocument doc, File imageFile) throws Exception {
        try (var iis = ImageIO.createImageInputStream(imageFile)) {
            var readers = iis == null ? null : ImageIO.getImageReaders(iis);
            if (readers == null || !readers.hasNext()) {
                throw new Exception("Impossible de lire l'image " + imageFile.getName());
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                int count = Math.max(1, reader.getNumImages(true));
                if (count == 1) {
                    // Conserve l'encodage d'origine (un JPEG reste un JPEG dans le spool)
                    addImagePage(doc, PDImageXObject.createFromFileByContent(imageFile, doc));
                } else {
                    for (int i = 0; i < count; i++) {
                        BufferedImage img = reader.read(i);
                        addImagePage(doc, LosslessFactory.createFromImage(doc, img));
                    }
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static void addImagePage(PDDocument doc, PDImageXObject pdImage) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);

        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();
        float margin = 20;
        float availableWidth = pageWidth - 2 * margin;
        float availableHeight = pageHeight - 2 * margin;

        float imgWidth = pdImage.getWidth();
        float imgHeight = pdImage.getHeight();
        float scale = Math.min(availableWidth / imgWidth, availableHeight / imgHeight);
        float drawWidth = imgWidth * scale;
        float drawHeight = imgHeight * scale;

        float x = margin + (availableWidth - drawWidth) / 2;
        float y = margin + (availableHeight - drawHeight) / 2;

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(pdImage, x, y, drawWidth, drawHeight);
        }
    }
}
