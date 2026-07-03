package fr.courel.lammprimante.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateServiceTest {

    @Test
    void versionPlusRecenteDetectee() {
        assertTrue(UpdateService.isNewer("v2.0.2", "2.0.1"));
        assertTrue(UpdateService.isNewer("2.1.0", "v2.0.9"));
        assertTrue(UpdateService.isNewer("3.0", "2.9.9"));
    }

    @Test
    void versionEgaleOuPlusAncienneIgnoree() {
        assertFalse(UpdateService.isNewer("v2.0.1", "2.0.1"));
        assertFalse(UpdateService.isNewer("1.9.9", "2.0.0"));
        assertFalse(UpdateService.isNewer("2.0", "2.0.0"));
    }

    @Test
    void entreesInvalidesSansCrash() {
        assertFalse(UpdateService.isNewer(null, "1.0.0"));
        assertFalse(UpdateService.isNewer("1.0.0", null));
        assertFalse(UpdateService.isNewer("abc", "1.0.0"));
    }
}
