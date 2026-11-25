package com.ccdev.transit.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平衡随机分配：在每个层（stratum）内按确定性哈希排序，
 * 取前 round(n*p) 个单元为处理组。偶数层 + p=0.5 时严格 50% 饱和。
 * 边缘包含概率为 t/n（层内每个单元等概率进入处理组）。
 */
public final class AssignmentEngine {

    public static final String TREATMENT = "TREATMENT";
    public static final String CONTROL = "CONTROL";

    public record UnitSpec(String unitKey, String stratum) {
    }

    public record Assignment(
            String unitKey,
            String stratum,
            String arm,
            String hash,
            double inclusionProb) {
    }

    public List<Assignment> assignBalanced(String seed, double p, List<UnitSpec> units) {
        Map<String, List<UnitSpec>> byStratum = new LinkedHashMap<>();
        for (UnitSpec u : units) {
            byStratum.computeIfAbsent(u.stratum(), k -> new ArrayList<>()).add(u);
        }

        Map<String, Assignment> byKey = new LinkedHashMap<>();
        for (List<UnitSpec> group : byStratum.values()) {
            int n = group.size();
            int t = (int) Math.round(n * p);
            if (n > 0 && p > 0) {
                t = Math.max(1, t);
            }
            if (p < 1.0) {
                t = Math.min(n - (p > 0 ? 1 : 0), t);
            }
            double prob = n == 0 ? 0.0 : t / (double) n;

            List<UnitSpec> sorted = new ArrayList<>(group);
            sorted.sort(Comparator.comparingDouble(u -> Deterministic.uniform01(seed, u.unitKey())));

            for (int i = 0; i < sorted.size(); i++) {
                UnitSpec u = sorted.get(i);
                double r = Deterministic.uniform01(seed, u.unitKey());
                String arm = i < t ? TREATMENT : CONTROL;
                byKey.put(u.unitKey(), new Assignment(
                        u.unitKey(), u.stratum(), arm,
                        Deterministic.sha256Hex(seed + "::" + u.unitKey()),
                        prob));
            }
        }

        // 按入参顺序返回
        List<Assignment> result = new ArrayList<>();
        for (UnitSpec u : units) {
            result.add(byKey.get(u.unitKey()));
        }
        return result;
    }

    /** 分配清单指纹：任何单元/臂变化都会改变哈希，供离线审阅核对。 */
    public static String manifestHash(List<Assignment> assignments) {
        List<String> lines = assignments.stream()
                .map(a -> a.unitKey() + "|" + a.stratum() + "|" + a.arm())
                .sorted()
                .toList();
        return Deterministic.sha256Hex(String.join("\n", lines));
    }

    public record Saturation(long treatment, long control, double treatmentShare) {
    }

    public static Saturation saturation(List<Assignment> assignments) {
        long t = assignments.stream().filter(a -> TREATMENT.equals(a.arm())).count();
        long c = assignments.size() - t;
        return new Saturation(t, c, assignments.isEmpty() ? 0 : t / (double) assignments.size());
    }
}
