package fr.jeremy.pdfprinter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.printing.PDFPageable;

import javax.imageio.ImageIO;
import javax.print.PrintService;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.PageRanges;
import java.awt.image.BufferedImage;
import java.awt.print.PrinterJob;
import java.io.File;
import java.util.Set;
import java.util.function.Consumer;

public class PdfPrintService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif", "tiff", "tif");

    public record PrintProgress(String fileName, int batchNumber, int totalBatches, int fromPage, int toPage) {}

    public static boolean isSupported(File file) {
        String ext = getExtension(file);
        return ext.equals("pdf") || IMAGE_EXTENSIONS.contains(ext);
    }

    public static String getExtension(File file) {
        String name = file.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    public void print(File file, int batchSize, PrintService printer, HashPrintRequestAttributeSet baseAttrs, Consumer<PrintProgress> onProgress) throws Exception {
        String ext = getExtension(file);
        if (IMAGE_EXTENSIONS.contains(ext)) {
            printImage(file, printer, baseAttrs, onProgress);
        } else {
            printPdf(file, batchSize, printer, baseAttrs, onProgress);
        }
    }

    private void printPdf(File pdfFile, int batchSize, PrintService printer, HashPrintRequestAttributeSet baseAttrs, Consumer<PrintProgress> onProgress) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int totalPages = document.getNumberOfPages();
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

                HashPrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet(baseAttrs);
                attrs.add(new PageRanges(fromPage + 1, toPage));
                job.print(attrs);
            }
        }
    }

    private void printImage(File imageFile, PrintService printer, HashPrintRequestAttributeSet baseAttrs, Consumer<PrintProgress> onProgress) throws Exception {
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
            job.print(baseAttrs);
        }
    }
}
