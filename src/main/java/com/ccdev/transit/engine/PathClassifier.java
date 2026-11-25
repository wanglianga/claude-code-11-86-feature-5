package com.ccdev.transit.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 按“完整旅程路径”判定暴露，而不是只看首乘线路：
 * <ul>
 *   <li>FULL_TREATMENT：所有可识别路段均实际享受到新规则；</li>
 *   <li>FULL_CONTROL：全程旧规则/对照服务，且没有搭乘异臂服务；</li>
 *   <li>CROSS_MIXED：路径中同时出现处理臂/对照臂服务（枢纽换乘、短线折返分流、
 *       对照乘客搭乘处理线路等），单独成类，绝不并入“未暴露对照”；</li>
 *   <li>UNKNOWN：未完成刷卡、中途改线或路段无法匹配，原样保留为未知。</li>
 * </ul>
 * 注意区分两个轴：分配臂（随机化，ITT 用）与实际暴露（规则是否真的执行）。
 */
public final class PathClassifier {

    public static final String FULL_TREATMENT = "FULL_TREATMENT";
    public static final String FULL_CONTROL = "FULL_CONTROL";
    public static final String CROSS_MIXED = "CROSS_MIXED";
    public static final String UNKNOWN = "UNKNOWN";

    public static final String CLUSTER_T = "CLUSTER_TREATMENT";
    public static final String CLUSTER_C = "CLUSTER_CONTROL";
    public static final String CLUSTER_CROSS = "CLUSTER_CROSS";

    public record LegView(
            int legIndex,
            String route,
            String plannedArm,      // 班次随机方案下该班次的分配臂
            String clusterArm,      // 簇—周方案下该簇周的分配臂
            boolean ruleExecuted,
            String deviation,
            boolean matched) {
    }

    public record PathView(
            String pathLabel,
            String clusterPathLabel,
            int treatmentLegs,
            int controlLegs,
            int crossLegs,
            int unknownLegs,
            boolean crossLine,
            boolean diverted,
            boolean effectiveTreatment,
            List<String> legExposures,
            List<String> trace) {
    }

    public PathView classify(String journeyStatus, List<LegView> legs) {
        List<String> exposures = new ArrayList<>();
        List<String> trace = new ArrayList<>();

        if (!"COMPLETE".equals(journeyStatus)) {
            trace.add("旅程状态=" + journeyStatus + "，按规则保留为未知，不参与效应估计");
            for (LegView leg : legs) {
                exposures.add("UNKNOWN");
            }
            return new PathView(UNKNOWN, UNKNOWN, 0, 0, 0,
                    Math.max(1, legs.size()), false, false, false, exposures, trace);
        }

        String anchorArm = legs.isEmpty() ? null : legs.get(0).plannedArm();
        int t = 0, c = 0, x = 0, u = 0;
        boolean diverted = false;
        boolean anyShortTurn = false;

        for (LegView leg : legs) {
            if (!leg.matched()) {
                u++;
                exposures.add("UNKNOWN");
                trace.add("第" + leg.legIndex() + "段(" + leg.route() + ")无法匹配计划/实际班次 → UNKNOWN");
                continue;
            }
            boolean suspended = "SUSPENDED".equals(leg.deviation());
            boolean effectiveT = AssignmentEngine.TREATMENT.equals(leg.plannedArm())
                    && leg.ruleExecuted() && !suspended;

            boolean oppositeArm = anchorArm != null && leg.plannedArm() != null
                    && !leg.plannedArm().equals(anchorArm);

            if (oppositeArm) {
                x++;
                exposures.add("CROSS");
                trace.add("第" + leg.legIndex() + "段(" + leg.route()
                        + ")分配臂=" + leg.plannedArm() + " 与起乘锚定臂 " + anchorArm
                        + " 不同 → 交叉/分流路段 CROSS");
            } else if (effectiveT) {
                t++;
                exposures.add("TREATMENT");
                trace.add("第" + leg.legIndex() + "段(" + leg.route()
                        + ")分配=T 且规则实际执行"
                        + ("SHORT_TURN".equals(leg.deviation()) ? "（短线折返，乘客继续后续换乘）" : "")
                        + " → TREATMENT");
            } else {
                c++;
                exposures.add("CONTROL");
                if (AssignmentEngine.TREATMENT.equals(leg.plannedArm()) && !leg.ruleExecuted()) {
                    trace.add("第" + leg.legIndex() + "段(" + leg.route()
                            + ")虽分配=T 但司机未执行规则 → 实际按旧规则服务 CONTROL（分配仍计入 ITT 处理组）");
                } else if (suspended) {
                    trace.add("第" + leg.legIndex() + "段(" + leg.route() + ")停运 → CONTROL/不可用");
                } else {
                    trace.add("第" + leg.legIndex() + "段(" + leg.route() + ")对照旧规则服务 → CONTROL");
                }
            }
            if ("SHORT_TURN".equals(leg.deviation())) {
                anyShortTurn = true;
            }
        }

        diverted = anyShortTurn && legs.size() > 1;
        if (diverted) {
            trace.add("检测到短线折返后接续其他线路，diverted=true");
        }

        String label;
        if (u > 0) {
            label = UNKNOWN;
        } else if (x > 0 || (t > 0 && c > 0)) {
            label = CROSS_MIXED;
        } else if (t > 0) {
            label = FULL_TREATMENT;
        } else {
            label = FULL_CONTROL;
        }

        // 簇—周口径：同簇线路在同一周同臂，枢纽换乘不再构成异臂污染
        String clusterLabel = classifyCluster(legs, trace);

        return new PathView(label, clusterLabel, t, c, x, u,
                x > 0, diverted, FULL_TREATMENT.equals(label),
                exposures, trace);
    }

    private String classifyCluster(List<LegView> legs, List<String> trace) {
        String arm = null;
        for (LegView leg : legs) {
            if (!leg.matched() || leg.clusterArm() == null) {
                return UNKNOWN;
            }
            if (arm == null) {
                arm = leg.clusterArm();
            } else if (!arm.equals(leg.clusterArm())) {
                trace.add("簇—周口径下路径跨越不同簇且周臂不同 → CLUSTER_CROSS");
                return CLUSTER_CROSS;
            }
        }
        if (arm == null) {
            return UNKNOWN;
        }
        return AssignmentEngine.TREATMENT.equals(arm) ? CLUSTER_T : CLUSTER_C;
    }
}
