package com.ccdev.transit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathClassifierTest {

    private final PathClassifier classifier = new PathClassifier();

    private PathClassifier.LegView leg(int i, String route, String arm, boolean executed, String dev) {
        return new PathClassifier.LegView(i, route, arm, "CONTROL", executed, dev, true);
    }

    @Test
    void divertR1TreatmentShortTurnToR2Control_isCrossMixedAndDiverted() {
        var pv = classifier.classify("COMPLETE", List.of(
                leg(1, "R1", "TREATMENT", true, "SHORT_TURN"),
                leg(2, "R2", "CONTROL", true, "NONE")));

        assertEquals(PathClassifier.CROSS_MIXED, pv.pathLabel());
        assertEquals(1, pv.crossLegs());
        assertTrue(pv.diverted());
        assertTrue(pv.crossLine());
        assertFalse(pv.effectiveTreatment());
    }

    @Test
    void ordinaryR2PassengerOnSameControlTrip_staysFullControl() {
        var pv = classifier.classify("COMPLETE", List.of(
                leg(1, "R2", "CONTROL", false, "NONE")));
        assertEquals(PathClassifier.FULL_CONTROL, pv.pathLabel());
        assertEquals(0, pv.crossLegs());
    }

    @Test
    void controlAnchorBoardingTreatmentTransfer_isCrossMixed() {
        var pv = classifier.classify("COMPLETE", List.of(
                leg(1, "R1", "CONTROL", false, "NONE"),
                leg(2, "R2", "TREATMENT", true, "NONE")));
        assertEquals(PathClassifier.CROSS_MIXED, pv.pathLabel());
        assertEquals(1, pv.crossLegs());
    }

    @Test
    void fullTreatmentAndFullControlPaths() {
        var t = classifier.classify("COMPLETE", List.of(
                leg(1, "R1", "TREATMENT", true, "NONE"),
                leg(2, "R2", "TREATMENT", true, "NONE")));
        assertEquals(PathClassifier.FULL_TREATMENT, t.pathLabel());
        assertTrue(t.effectiveTreatment());

        var c = classifier.classify("COMPLETE", List.of(
                leg(1, "R1", "CONTROL", false, "NONE"),
                leg(2, "R2", "CONTROL", false, "NONE")));
        assertEquals(PathClassifier.FULL_CONTROL, c.pathLabel());
    }

    @Test
    void assignedTreatmentButDriverDidNotExecute_countsAsActualControlYetAssignedT() {
        var pv = classifier.classify("COMPLETE", List.of(
                leg(1, "R1", "TREATMENT", false, "NONE")));
        assertEquals(PathClassifier.FULL_CONTROL, pv.pathLabel());
        assertEquals(0, pv.treatmentLegs());
        assertEquals(1, pv.controlLegs());
    }

    @Test
    void incompleteTapAndMidRouteChange_areUnknown() {
        for (String status : List.of("INCOMPLETE_TAP", "MID_ROUTE_CHANGE")) {
            var pv = classifier.classify(status, List.of(leg(1, "R1", "TREATMENT", true, "SUSPENDED")));
            assertEquals(PathClassifier.UNKNOWN, pv.pathLabel());
        }
    }

    @Test
    void unmatchedLegForcesUnknown_forConservativeExposure() {
        var unmatched = new PathClassifier.LegView(
                1, "R9", "CONTROL", "CONTROL", true, "NONE", false);
        var pv = classifier.classify("COMPLETE", List.of(unmatched));
        assertEquals(PathClassifier.UNKNOWN, pv.pathLabel());
        assertEquals(1, pv.unknownLegs());
    }
}
