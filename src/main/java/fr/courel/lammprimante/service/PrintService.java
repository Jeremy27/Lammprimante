package fr.courel.lammprimante.service;

import fr.courel.lammprimante.model.PrintSettings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.printing.PDFPageable;

import javax.imageio.ImageIO;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.PageRanges;
import java.awt.image.BufferedImage;
import java.awt.print.PrinterJob;
import java.io.File;
import java.util.function.Consumer;

public class PrintService {

    public record PrintProgress(String fileName, int batchNumber, int totalBatches, int fromPage, int toPage) {}

    public void print(File file, javax.print.PrintService printer, PrintSettings settings,
                      Consumer<PrintProgress> onProgress) throws Exception {
        if (FileImportService.isImage(file)) {
            printImage(file, printer, settings, onProgress);
        } else {
            printPdf(file, printer, settings, onProgress);
        }
    }

    private void printPdf(File pdfFile, javax.print.PrintService printer, PrintSettings settings,
                          Consumer<PrintProgress> onProgress) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int totalPages = document.getNumberOfPages();
            int batchSize = settings.batchSize();
            int totalBatches = (int) Math.ceil((double) totalPages / batchSize);

            for (int batch = 0; batch < totalBatches; batch++) {
                int fromPage = batch * batchSize;
                int toPage = Math.min(fromPage + batchSize, totalPages);

                onProgress.accept(new PrintProgress(
                        pdfFile.getName(), batch + 1, totalBatches, fromPage + 1, toPage));

                PrinterJob job = PrinterJob.getPrinterJob();
                job.setPrintService(printer);
                job.setPageable(new PDFPageable(document));
                job.setJobName(pdfFile.getName() + " - lot " + (batch + 1) + "/" + totalBatches);

                HashPrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet(settings.toAttributes());
                attrs.add(new PageRanges(fromPage + 1, toPage));
                job.print(attrs);
            }
        }
    }

    private void printImage(File imageFile, javax.print.PrintService printer, PrintSettings settings,
                            Consumer<PrintProgress> onProgress) throws Exception {
        BufferedImage img = ImageIO.read(imageFile);
        if (img == null) {
            throw new Exception("Impossible de lire l'image");
        }

        try (PDDocument doc = new PDDocument()) {
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

            onProgress.accept(new PrintProgress(imageFile.getName(), 1, 1, 1, 1));

            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(printer);
            job.setPageable(new PDFPageable(doc));
            job.setJobName(imageFile.getName());
            job.print(settings.toAttributes());
        }
    }
}
