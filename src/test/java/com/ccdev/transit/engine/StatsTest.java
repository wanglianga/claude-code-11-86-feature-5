package com.ccdev.transit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {

    @Test
    void clusterItRecoversKnownConstantEffect() {
        List<Stats.Observation> obs = new ArrayList<>();
        // 20 个随机化单元，每单元 10 名乘客；处理效应固定 -60 秒
        for (int g = 0; g < 20; g++) {
            int a = g % 2;
            for (int i = 0; i < 10; i++) {
                double y = 400 + (i % 5) * 7 - (a == 1 ? 60 : 0);
                obs.add(new Stats.Observation(y, 1, a, "U" + g));
            }
        }
        Stats.EffectEstimate fit = Stats.clusterITT(obs);
        assertEquals(-60.0, fit.estimate(), 1e-8);
        assertEquals(20, fit.groups());
        assertTrue(fit.se() >= 0);
        assertTrue(fit.ciLow() <= -60 && fit.ciHigh() >= -60);
    }

    @Test
    void wilsonIntervalBoundsRate() {
        double[] w = Stats.wilson(20, 100, 1.96);
        assertEquals(0.20, w[0], 1e-9);
        assertTrue(w[1] < 0.20 && w[2] > 0.20);
        assertTrue(w[1] >= 0 && w[2] <= 1);
    }

    @Test
    void weightedMeanRespectsWeights() {
        double m = Stats.weightedMean(List.of(100.0, 200.0), List.of(3.0, 1.0));
        assertEquals(125.0, m, 1e-9);
    }

    @Test
    void randomizationInferenceFindsExtremeAssignment() {
        // 4 周、2 簇：处理簇结果低 50；观测方向全部为簇0处理
        List<Stats.RiRow> rows = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            for (int i = 0; i < 20; i++) {
                rows.add(new Stats.RiRow(w, 0, 300 + (i % 3), 1));
                rows.add(new Stats.RiRow(w, 1, 360 + (i % 3), 1));
            }
        }
        List<int[]> perms = new ArrayList<>();
        for (int mask = 0; mask < 16; mask++) {
            int[] o = new int[4];
            for (int w = 0; w < 4; w++) o[w] = (mask >> w) & 1;
            perms.add(o);
        }
        double[] ri = Stats.randomizationInferenceClusterWeeks(rows, perms, new int[]{0, 0, 0, 0});
        assertEquals(-60.0, ri[1], 0.5);
        // 全 0 与全 1 两个方向同样极端，plus-one 后 p=(1+2)/17
        assertEquals(3.0 / 17.0, ri[0], 1e-9);
    }
}
