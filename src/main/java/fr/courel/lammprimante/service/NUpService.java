package fr.courel.lammprimante.service;

import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.AffineTransform;
import java.io.IOException;

public class NUpService {

    public PDDocument compose(PDDocument source, int pagesPerSheet) throws IOException {
        if (pagesPerSheet <= 1) {
            throw new IllegalArgumentException("pagesPerSheet doit être > 1");
        }

        int cols;
        int rows;
        PDRectangle sheetSize;
        if (pagesPerSheet == 2) {
            cols = 2;
            rows = 1;
            sheetSize = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        } else if (pagesPerSheet == 4) {
            cols = 2;
            rows = 2;
            sheetSize = PDRectangle.A4;
        } else {
            throw new IllegalArgumentException("pagesPerSheet non supporté : " + pagesPerSheet);
        }

        PDDocument result = new PDDocument();
        LayerUtility layerUtility = new LayerUtility(result);

        float sheetWidth = sheetSize.getWidth();
        float sheetHeight = sheetSize.getHeight();
        float margin = 14f;
        float cellWidth = (sheetWidth - 2 * margin) / cols;
        float cellHeight = (sheetHeight - 2 * margin) / rows;

        int sourcePages = source.getNumberOfPages();
        int sheetCount = (int) Math.ceil((double) sourcePages / pagesPerSheet);

        for (int sheetIdx = 0; sheetIdx < sheetCount; sheetIdx++) {
            PDPage sheet = new PDPage(sheetSize);
            result.addPage(sheet);

            try (PDPageContentStream cs = new PDPageContentStream(
                    result, sheet, PDPageContentStream.AppendMode.APPEND, false)) {

                for (int slot = 0; slot < pagesPerSheet; slot++) {
                    int sourceIdx = sheetIdx * pagesPerSheet + slot;
                    if (sourceIdx >= sourcePages) {
                        break;
                    }

                    PDFormXObject form = layerUtility.importPageAsForm(source, sourceIdx);
                    PDRectangle bbox = form.getBBox();
                    float srcWidth = bbox.getWidth();
                    float srcHeight = bbox.getHeight();
                    if (srcWidth <= 0 || srcHeight <= 0) {
                        continue;
                    }

                    float scale = Math.min(cellWidth / srcWidth, cellHeight / srcHeight);
                    float drawWidth = srcWidth * scale;
                    float drawHeight = srcHeight * scale;

                    int col = slot % cols;
                    int row = slot / cols;
                    float cellOriginX = margin + col * cellWidth;
                    float cellOriginY = sheetHeight - margin - (row + 1) * cellHeight;
                    float offsetX = cellOriginX + (cellWidth - drawWidth) / 2 - bbox.getLowerLeftX() * scale;
                    float offsetY = cellOriginY + (cellHeight - drawHeight) / 2 - bbox.getLowerLeftY() * scale;

                    AffineTransform at = new AffineTransform();
                    at.translate(offsetX, offsetY);
                    at.scale(scale, scale);

                    cs.saveGraphicsState();
                    cs.transform(new Matrix(at));
                    cs.drawForm(form);
                    cs.restoreGraphicsState();
                }
            }
        }

        return result;
    }
}
