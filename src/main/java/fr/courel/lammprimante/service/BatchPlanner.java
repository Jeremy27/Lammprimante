package fr.courel.lammprimante.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Calcul pur du découpage en lots. Le découpage en plusieurs jobs est une
 * contrainte dure (le spooler de l'imprimante cible refuse les gros jobs) :
 * ne jamais fusionner les lots pour contourner un autre problème.
 */
public final class BatchPlanner {

    /** Pages 0-based, borne haute exclusive. */
    public record Batch(int fromPage, int toPage) {
        public int size() {
            return toPage - fromPage;
        }
    }

    private BatchPlanner() {
    }

    /**
     * En duplex, un lot impair force le driver à éjecter la dernière feuille
     * avec un verso blanc avant le job suivant → on force un lot pair.
     * Un lot de 1 est remonté à 2 (1 page par job rendrait le duplex impossible).
     */
    public static int effectiveBatchSize(int requested, boolean duplex) {
        int size = Math.max(1, requested);
        if (!duplex || size % 2 == 0) {
            return size;
        }
        return size == 1 ? 2 : size - 1;
    }

    public static List<Batch> plan(int fromPage, int toPage, int requestedBatchSize, boolean duplex) {
        int size = effectiveBatchSize(requestedBatchSize, duplex);
        List<Batch> batches = new ArrayList<>();
        int from = Math.max(0, fromPage);
        while (from < toPage) {
            int end = Math.min(from + size, toPage);
            batches.add(new Batch(from, end));
            from = end;
        }
        return batches;
    }
}
