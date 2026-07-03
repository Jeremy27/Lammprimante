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

    /**
     * Attributs d'un job d'impression. Toujours Copies(1) : les copies sont
     * bouclées au niveau du document par PrintService pour sortir des
     * exemplaires complets collationnés (Copies(n) par lot donnerait
     * lot1,lot1,lot2,lot2…).
     */
    public HashPrintRequestAttributeSet toAttributes() {
        HashPrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();

        attrs.add(new Copies(1));

        attrs.add(switch (duplex) {
            case LONG_EDGE -> Sides.TWO_SIDED_LONG_EDGE;
            case SHORT_EDGE -> Sides.TWO_SIDED_SHORT_EDGE;
            default -> Sides.ONE_SIDED;
        });

        attrs.add(color == ColorMode.MONOCHROME ? Chromaticity.MONOCHROME : Chromaticity.COLOR);

        // Quand N-up est actif, le doc est déjà recomposé en paysage (2-up) ou portrait (4-up).
        OrientationRequested effectiveOrientation;
        if (pagesPerSheet == 2) {
            effectiveOrientation = OrientationRequested.LANDSCAPE;
        } else if (pagesPerSheet == 4) {
            effectiveOrientation = OrientationRequested.PORTRAIT;
        } else {
            effectiveOrientation = orientation == Orientation.LANDSCAPE
                    ? OrientationRequested.LANDSCAPE
                    : OrientationRequested.PORTRAIT;
        }
        attrs.add(effectiveOrientation);

        return attrs;
    }
}
