package fr.courel.lammprimante.model;

import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.*;

public record PrintSettings(
        int batchSize,
        int copies,
        DuplexMode duplex,
        Orientation orientation,
        ColorMode color,
        int pagesPerSheet
) {
    public enum DuplexMode { RECTO, LONG_EDGE, SHORT_EDGE }
    public enum Orientation { PORTRAIT, LANDSCAPE }
    public enum ColorMode { COLOR, MONOCHROME }

    public HashPrintRequestAttributeSet toAttributes() {
        HashPrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();

        attrs.add(new Copies(copies));

        attrs.add(switch (duplex) {
            case LONG_EDGE -> Sides.TWO_SIDED_LONG_EDGE;
            case SHORT_EDGE -> Sides.TWO_SIDED_SHORT_EDGE;
            default -> Sides.ONE_SIDED;
        });

        attrs.add(color == ColorMode.MONOCHROME ? Chromaticity.MONOCHROME : Chromaticity.COLOR);
        attrs.add(orientation == Orientation.LANDSCAPE ? OrientationRequested.LANDSCAPE : OrientationRequested.PORTRAIT);

        if (pagesPerSheet > 1) {
            attrs.add(new NumberUp(pagesPerSheet));
        }

        return attrs;
    }
}
