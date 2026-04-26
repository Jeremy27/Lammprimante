package fr.courel.lammprimante.service;

import fr.courel.lammprimante.model.PrintSettings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.printing.PDFPageable;

import javax.imageio.ImageIO;
import javax.print.attribute.HashPrintRequestAttributeSet;
import java.awt.image.BufferedImage;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

public class PrintService {

    private static final long[] RETRY_DELAYS_MS = {2_000L, 10_000L, 30_000L};

    public record PrintProgress(String fileName, int batchNumber, int totalBatches, int fromPage, int toPage) {}

    public static class PartialPrintException extends Exception {
        private final int resumePage;
        public PartialPrintException(int resumePage, Throwable cause) {
            super(cause.getMessage(), cause);
            this.resumePage = resumePage;
        }
        public int getResumePage() { return resumePage; }
    }

    public void print(File file, javax.print.PrintService printer, PrintSettings settings,
                      Consumer<PrintProgress> onProgress) throws Exception {
        print(file, printer, settings, 0, onProgress);
    }

    public void print(File file, javax.print.PrintService printer, PrintSettings settings,
                      int startPage, Consumer<PrintProgress> onProgress) throws Exception {
        if (FileImportService.isImage(file)) {
            printImage(file, printer, settings, onProgress);
        } else {
            printPdf(file, printer, settings, startPage, onProgress);
        }
    }

    private void printPdf(File pdfFile, javax.print.PrintService printer, PrintSettings settings,
                          int startPage, Consumer<PrintProgress> onProgress) throws Exception {
        long fileStart = System.nanoTime();
        long loadStart = System.nanoTime();
        try (PDDocument loaded = Loader.loadPDF(pdfFile)) {
            long loadMs = (System.nanoTime() - loadStart) / 1_000_000;
            LogService.info(String.format("[%s] loadPDF=%dms (taille=%dKo)",
                    pdfFile.getName(), loadMs, pdfFile.length() / 1024));

            PDDocument document = loaded;
            PDDocument composed = null;
            try {
                if (settings.pagesPerSheet() > 1) {
                    long nupStart = System.nanoTime();
                    composed = new NUpService().compose(loaded, settings.pagesPerSheet());
                    document = composed;
                    LogService.info(String.format("[%s] N-up compose=%dms",
                            pdfFile.getName(), (System.nanoTime() - nupStart) / 1_000_000));
                }

                int totalPages = document.getNumberOfPages();
                // En duplex, un lot impair force le driver à ajouter un verso blanc
                // avant le job suivant pour éjecter la feuille → on force un batch pair.
                int batchSize = settings.batchSize();
                if (settings.duplex() != PrintSettings.DuplexMode.RECTO && batchSize > 1 && batchSize % 2 != 0) {
                    batchSize--;
                }
                int clampedStart = Math.max(0, Math.min(startPage, totalPages));
                int remainingPages = totalPages - clampedStart;
                int totalBatches = remainingPages == 0 ? 0 : (int) Math.ceil((double) remainingPages / batchSize);

                long totalSplitMs = 0;
                long totalPrintMs = 0;

                int fromPage = clampedStart;
                for (int batch = 0; batch < totalBatches; batch++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Impression annulée");
                    }
                    int toPage = Math.min(fromPage + batchSize, totalPages);
                    boolean wholeDoc = (totalBatches == 1 && fromPage == 0);

                    // Cas wholeDoc : on imprime `document` directement (économise une
                    // copie complète via Splitter, qui copie toutes les ressources du
                    // PDF — coûteux pour les fichiers riches en images).
                    // Sinon : on extrait un sous-document avec uniquement les pages du
                    // lot (PageRanges via Pageable est ignoré par certains drivers).
                    long splitStart = System.nanoTime();
                    PDDocument batchDoc;
                    List<PDDocument> parts = null;
                    if (wholeDoc) {
                        batchDoc = document;
                    } else {
                        Splitter splitter = new Splitter();
                        splitter.setStartPage(fromPage + 1);
                        splitter.setEndPage(toPage);
                        splitter.setSplitAtPage(toPage - fromPage);
                        parts = splitter.split(document);
                        batchDoc = parts.get(0);
                    }
                    long splitMs = (System.nanoTime() - splitStart) / 1_000_000;
                    totalSplitMs += splitMs;
                    try {
                        String jobName = pdfFile.getName() + " - lot " + (batch + 1) + "/" + totalBatches;
                        long printStart = System.nanoTime();
                        try {
                            printWithRetry(printer, batchDoc, jobName, settings.toAttributes(),
                                    pdfFile.getName(), batch + 1, totalBatches);
                        } catch (PrinterException ex) {
                            throw new PartialPrintException(fromPage, ex);
                        }
                        long printMs = (System.nanoTime() - printStart) / 1_000_000;
                        totalPrintMs += printMs;
                        LogService.info(String.format("[%s] Lot %d/%d : split=%dms%s, print=%dms",
                                pdfFile.getName(), batch + 1, totalBatches, splitMs,
                                wholeDoc ? " (skip)" : "", printMs));
                    } finally {
                        if (parts != null) {
                            for (PDDocument part : parts) {
                                part.close();
                            }
                        }
                    }

                    onProgress.accept(new PrintProgress(
                            pdfFile.getName(), batch + 1, totalBatches, fromPage + 1, toPage));

                    fromPage = toPage;
                }

                long closeStart = System.nanoTime();
                if (composed != null) {
                    composed.close();
                    composed = null;
                }
                long composedCloseMs = (System.nanoTime() - closeStart) / 1_000_000;
                LogService.info(String.format(
                        "[%s] récap : load=%dms, splits=%dms, prints=%dms, composed-close=%dms, total=%dms",
                        pdfFile.getName(), loadMs, totalSplitMs, totalPrintMs, composedCloseMs,
                        (System.nanoTime() - fileStart) / 1_000_000));
            } finally {
                if (composed != null) {
                    composed.close();
                }
            }
        }
        LogService.info(String.format("[%s] loaded.close inclus ; total fichier (avec close)=%dms",
                pdfFile.getName(), (System.nanoTime() - fileStart) / 1_000_000));
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

    private void printImage(File imageFile, javax.print.PrintService printer, PrintSettings settings,
                            Consumer<PrintProgress> onProgress) throws Exception {
        long fileStart = System.nanoTime();
        long decodeStart = System.nanoTime();
        BufferedImage img = ImageIO.read(imageFile);
        if (img == null) {
            throw new Exception("Impossible de lire l'image");
        }
        long decodeMs = (System.nanoTime() - decodeStart) / 1_000_000;

        try (PDDocument doc = new PDDocument()) {
            long renderStart = System.nanoTime();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDImageXObject pdImage = PDImageXObject.createFromFileByContent(imageFile, doc);

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
            long renderMs = (System.nanoTime() - renderStart) / 1_000_000;

            onProgress.accept(new PrintProgress(imageFile.getName(), 1, 1, 1, 1));

            long printStart = System.nanoTime();
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(printer);
            job.setPageable(new PDFPageable(doc));
            job.setJobName(imageFile.getName());
            job.print(settings.toAttributes());
            long printMs = (System.nanoTime() - printStart) / 1_000_000;

            LogService.info(String.format(
                    "[%s] image : decode=%dms, render=%dms, print=%dms, total=%dms",
                    imageFile.getName(), decodeMs, renderMs, printMs,
                    (System.nanoTime() - fileStart) / 1_000_000));
        }
    }
}
