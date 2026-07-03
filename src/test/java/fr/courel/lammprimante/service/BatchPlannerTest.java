package fr.courel.lammprimante.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchPlannerTest {

    @Test
    void effectiveBatchSizeUnchangedEnRecto() {
        assertEquals(25, BatchPlanner.effectiveBatchSize(25, false));
        assertEquals(1, BatchPlanner.effectiveBatchSize(1, false));
    }

    @Test
    void effectiveBatchSizePairEnDuplex() {
        assertEquals(24, BatchPlanner.effectiveBatchSize(25, true));
        assertEquals(20, BatchPlanner.effectiveBatchSize(20, true));
    }

    @Test
    void batchDeUnDevientDeuxEnDuplex() {
        assertEquals(2, BatchPlanner.effectiveBatchSize(1, true));
    }

    @Test
    void planExactMultiple() {
        List<BatchPlanner.Batch> batches = BatchPlanner.plan(0, 40, 20, false);
        assertEquals(2, batches.size());
        assertEquals(new BatchPlanner.Batch(0, 20), batches.get(0));
        assertEquals(new BatchPlanner.Batch(20, 40), batches.get(1));
    }

    @Test
    void planDernierLotPartiel() {
        List<BatchPlanner.Batch> batches = BatchPlanner.plan(0, 45, 20, false);
        assertEquals(3, batches.size());
        assertEquals(new BatchPlanner.Batch(40, 45), batches.get(2));
    }

    @Test
    void planDuplexToutLesLotsSaufDernierSontPairs() {
        List<BatchPlanner.Batch> batches = BatchPlanner.plan(0, 100, 25, true);
        for (int i = 0; i < batches.size() - 1; i++) {
            assertEquals(0, batches.get(i).size() % 2,
                "lot " + i + " impair : " + batches.get(i));
        }
        int total = batches.stream().mapToInt(BatchPlanner.Batch::size).sum();
        assertEquals(100, total);
    }

    @Test
    void planRepriseEnMilieuDeDocument() {
        List<BatchPlanner.Batch> batches = BatchPlanner.plan(40, 100, 20, false);
        assertEquals(3, batches.size());
        assertEquals(40, batches.get(0).fromPage());
        assertEquals(100, batches.get(batches.size() - 1).toPage());
    }

    @Test
    void planVide() {
        assertTrue(BatchPlanner.plan(0, 0, 20, false).isEmpty());
        assertTrue(BatchPlanner.plan(10, 10, 20, true).isEmpty());
        assertTrue(BatchPlanner.plan(15, 10, 20, true).isEmpty());
    }

    @Test
    void planCouvreToutSansChevauchement() {
        List<BatchPlanner.Batch> batches = BatchPlanner.plan(3, 87, 7, true);
        int expected = 3;
        for (BatchPlanner.Batch b : batches) {
            assertEquals(expected, b.fromPage());
            assertTrue(b.size() > 0);
            expected = b.toPage();
        }
        assertEquals(87, expected);
    }
}
