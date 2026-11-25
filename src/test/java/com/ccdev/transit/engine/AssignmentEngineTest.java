package com.ccdev.transit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentEngineTest {

    private final AssignmentEngine engine = new AssignmentEngine();

    @Test
    void balancedAssignmentIsExactlyFiftyFiftyWithinEachStratum() {
        List<AssignmentEngine.UnitSpec> units = new ArrayList<>();
        for (String route : List.of("R1", "R2", "R3")) {
            for (int d = 1; d <= 20; d++) {
                String stratum = "DAY:" + route + ":2026-06-" + String.format("%02d", d);
                for (int t = 1; t <= 8; t++) {
                    units.add(new AssignmentEngine.UnitSpec(
                            "TRIP:" + route + ":d" + d + ":T" + t, stratum));
                }
            }
        }
        List<AssignmentEngine.Assignment> a1 =
                engine.assignBalanced("seed-x#TRIP", 0.5, units);
        List<AssignmentEngine.Assignment> a2 =
                engine.assignBalanced("seed-x#TRIP", 0.5, units);

        // 确定性：同种子结果完全一致
        for (int i = 0; i < a1.size(); i++) {
            assertEquals(a1.get(i).arm(), a2.get(i).arm());
        }

        long t = a1.stream().filter(a -> "TREATMENT".equals(a.arm())).count();
        assertEquals(240, t);                 // 480 * 0.5
        assertEquals(480 - 240, a1.size() - t);

        // 每个层严格 4/4，边缘概率 0.5
        var byStratum = a1.stream().collect(java.util.stream.Collectors.groupingBy(
                AssignmentEngine.Assignment::stratum));
        assertEquals(60, byStratum.size());
        for (var e : byStratum.entrySet()) {
            long tt = e.getValue().stream().filter(a -> "TREATMENT".equals(a.arm())).count();
            assertEquals(4, tt, "层 " + e.getKey() + " 必须恰好 4 个处理");
            assertEquals(0.5, e.getValue().get(0).inclusionProb(), 1e-9);
        }
    }

    @Test
    void differentSeedsChangeManifestButKeepBalance() {
        List<AssignmentEngine.UnitSpec> units = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            units.add(new AssignmentEngine.UnitSpec("CW:C1:W" + i, "W"));
        }
        var a = engine.assignBalanced("s1", 0.5, units);
        var b = engine.assignBalanced("s2", 0.5, units);
        assertEquals(4, a.stream().filter(x -> "TREATMENT".equals(x.arm())).count());
        assertNotEquals(AssignmentEngine.manifestHash(a), AssignmentEngine.manifestHash(b));
        // 哈希指纹稳定
        assertEquals(AssignmentEngine.manifestHash(a), AssignmentEngine.manifestHash(
                engine.assignBalanced("s1", 0.5, units)));
    }

    @Test
    void uniformHashIsInUnitInterval() {
        for (int i = 0; i < 1000; i++) {
            double u = Deterministic.uniform01("seed", "k" + i);
            assertTrue(u >= 0 && u < 1);
        }
    }
}
