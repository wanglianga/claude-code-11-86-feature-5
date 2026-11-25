package com.ccdev.transit.service;

import com.ccdev.transit.engine.PathClassifier;
import com.ccdev.transit.engine.Stats;
import com.ccdev.transit.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线可重算流水线：暴露路径 -> 共同支持分层 -> 估计量 -> 护栏。
 * 所有结果均由 PostgreSQL 中的冻结登记/分配与运营数据重新计算，可重复执行。
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final JdbcTemplate jdbc;
    private final PathClassifier classifier = new PathClassifier();

    public PipelineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void rebuildAll(long experimentId) {
        rebuildExposure(experimentId);
        rebuildSupport(experimentId);
        runAnalysis(experimentId);
        evaluateGuardrails(experimentId);
        log.info("实验 {} 流水线重算完成", experimentId);
    }

    // ============================ 暴露账 ============================

    public int rebuildExposure(long expId) {
        jdbc.update("DELETE FROM exposure_ledger WHERE experiment_id=?", expId);

        Map<String, String> cwArm = new LinkedHashMap<>();
        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT unit_key, arm FROM assignment_unit WHERE experiment_id=? AND design_type='CLUSTER_WEEK'",
                expId)) {
            cwArm.put(String.valueOf(m.get("unit_key")), String.valueOf(m.get("arm")));
        }
        Map<String, String> routeCluster = new LinkedHashMap<>();
        jdbc.queryForList("SELECT cluster_code, route_codes FROM route_cluster WHERE experiment_id=?", expId)
                .forEach(m -> {
                    for (String r : String.valueOf(m.get("route_codes")).split(",")) {
                        routeCluster.put(r.trim(), String.valueOf(m.get("cluster_code")));
                    }
                });

        // 平衡分配下各层 n/t，用于精确路径配置概率
        Map<String, int[]> stratumNT = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT stratum, count(*) n,
                       count(*) FILTER (WHERE arm='TREATMENT') t
                FROM assignment_unit WHERE experiment_id=? AND design_type='TRIP_RANDOMIZED'
                GROUP BY stratum
                """, expId).forEach(m -> stratumNT.put(String.valueOf(m.get("stratum")),
                new int[]{((Number) m.get("n")).intValue(), ((Number) m.get("t")).intValue()}));
        Map<String, String[]> unitMeta = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT unit_key, stratum, arm FROM assignment_unit
                WHERE experiment_id=? AND design_type='TRIP_RANDOMIZED'
                """, expId).forEach(m -> unitMeta.put(String.valueOf(m.get("unit_key")),
                new String[]{String.valueOf(m.get("stratum")), String.valueOf(m.get("arm"))}));

        List<Map<String, Object>> journeys = jdbc.queryForList(
                "SELECT id, journey_code, status, week_index FROM card_journey WHERE experiment_id=? ORDER BY id",
                expId);

        int written = 0;
        for (Map<String, Object> j : journeys) {
            long jid = ((Number) j.get("id")).longValue();
            String status = String.valueOf(j.get("status"));
            int week = ((Number) j.get("week_index")).intValue();

            List<Map<String, Object>> legRows = jdbc.queryForList("""
                    SELECT l.id, l.leg_index, l.route_code, l.unit_key, l.planned_arm,
                           l.trip_id, l.rule_effective,
                           COALESCE(r.rule_executed, l.rule_effective) AS run_executed,
                           COALESCE(r.deviation, l.deviation, 'NONE') AS deviation
                    FROM journey_leg l
                    LEFT JOIN actual_service_run r ON r.id = l.actual_run_id
                    WHERE l.journey_id=? ORDER BY l.leg_index
                    """, jid);

            List<PathClassifier.LegView> views = new ArrayList<>();
            List<String> legExposures = new ArrayList<>();
            for (Map<String, Object> lr : legRows) {
                String route = String.valueOf(lr.get("route_code"));
                String cc = routeCluster.get(route);
                String cwKey = cc == null ? null : "CW:" + cc + ":W" + week;
                String clusterArm = cwKey == null ? null : cwArm.get(cwKey);
                boolean matched = lr.get("trip_id") != null;
                boolean executed = lr.get("run_executed") != null
                        && Boolean.parseBoolean(String.valueOf(lr.get("run_executed")));
                if (lr.get("run_executed") == null) {
                    executed = Boolean.TRUE.equals(lr.get("rule_effective"));
                }
                views.add(new PathClassifier.LegView(
                        ((Number) lr.get("leg_index")).intValue(),
                        route,
                        lr.get("planned_arm") == null ? null : String.valueOf(lr.get("planned_arm")),
                        clusterArm,
                        executed,
                        String.valueOf(lr.get("deviation")),
                        matched));
            }

            PathClassifier.PathView pv = classifier.classify(status, views);
            legExposures.addAll(pv.legExposures());
            for (int i = 0; i < legRows.size(); i++) {
                jdbc.update("UPDATE journey_leg SET leg_exposure=? WHERE id=?",
                        legExposures.get(i), ((Number) legRows.get(i).get("id")).longValue());
            }

            String assignedTrip = legRows.isEmpty() || legRows.get(0).get("planned_arm") == null
                    ? null : String.valueOf(legRows.get(0).get("planned_arm"));
            String assignedCluster = null;
            if (!legRows.isEmpty()) {
                String route = String.valueOf(legRows.get(0).get("route_code"));
                String cc = routeCluster.get(route);
                assignedCluster = cc == null ? null : cwArm.get("CW:" + cc + ":W" + week);
            }

            Double pathProb = status.equals("COMPLETE")
                    ? pathProbability(legRows, unitMeta, stratumNT) : null;

            jdbc.update("""
                    INSERT INTO exposure_ledger(journey_id,experiment_id,journey_code,status,path_label,
                        cluster_path_label,treatment_legs,control_legs,cross_legs,unknown_legs,
                        assigned_arm_trip,assigned_arm_cluster,effective_treatment,cross_line,diverted,
                        path_probability,path_detail,rebuilt_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb, now())
                    """,
                    jid, expId, String.valueOf(j.get("journey_code")), status, pv.pathLabel(),
                    pv.clusterPathLabel(), pv.treatmentLegs(), pv.controlLegs(), pv.crossLegs(),
                    pv.unknownLegs(), assignedTrip, assignedCluster, pv.effectiveTreatment(),
                    pv.crossLine(), pv.diverted(), pathProb, Json.write(pv.trace()));
            written++;
        }
        return written;
    }

    /** 精确的无放回平衡分配路径概率：Π C(n-r_s, t_s-k_s)/C(n_s,t_s)。 */
    private Double pathProbability(List<Map<String, Object>> legRows,
                                   Map<String, String[]> unitMeta,
                                   Map<String, int[]> stratumNT) {
        // 路径用到的去重单元 -> (stratum, arm)
        Map<String, String[]> units = new LinkedHashMap<>();
        for (Map<String, Object> lr : legRows) {
            Object uk = lr.get("unit_key");
            if (uk == null) continue;
            String key = String.valueOf(uk);
            String[] meta = unitMeta.get(key);
            if (meta != null) {
                units.putIfAbsent(key, meta);
            }
        }
        if (units.isEmpty()) return null;

        Map<String, List<String[]>> byStratum = new LinkedHashMap<>();
        units.values().forEach(v -> byStratum.computeIfAbsent(v[0], k -> new ArrayList<>()).add(v));

        double prob = 1.0;
        for (Map.Entry<String, List<String[]>> e : byStratum.entrySet()) {
            int[] nt = stratumNT.get(e.getKey());
            if (nt == null) return null;
            int n = nt[0], t = nt[1];
            int r = e.getValue().size();
            int k = (int) e.getValue().stream().filter(v -> "TREATMENT".equals(v[1])).count();
            if (t - k < 0 || n - t - (r - k) < 0 || n - r < 0) return null;
            double ways = choose(n - r, t - k);
            double total = choose(n, t);
            prob *= ways / total;
        }
        return prob;
    }

    private static double choose(int n, int k) {
        if (k < 0 || k > n) return 0;
        k = Math.min(k, n - k);
        double v = 1;
        for (int i = 1; i <= k; i++) {
            v = v * (n - k + i) / i;
        }
        return v;
    }

    // ============================ 共同支持 ============================

    public int rebuildSupport(long expId) {
        jdbc.update("DELETE FROM support_stratum WHERE experiment_id=?", expId);
        Map<String, Object> exp = jdbc.queryForMap("SELECT analysis_config FROM experiment WHERE id=?", expId);
        int minW = 25;
        try {
            minW = ((Number) Json.read(String.valueOf(exp.get("analysis_config")), Map.class)
                    .getOrDefault("min_weighted_per_arm", 25)).intValue();
        } catch (Exception ignored) {
            // 默认 25
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT period_band, od_band, weather_class, accessibility,
                       count(*) n_total,
                       coalesce(sum(passenger_count),0) weighted_total,
                       count(*) FILTER (WHERE anchor_arm_trip='TREATMENT') n_t_trip,
                       count(*) FILTER (WHERE anchor_arm_trip='CONTROL') n_c_trip,
                       coalesce(sum(passenger_count) FILTER (WHERE anchor_arm_trip='TREATMENT'),0) w_t_trip,
                       coalesce(sum(passenger_count) FILTER (WHERE anchor_arm_trip='CONTROL'),0) w_c_trip,
                       count(*) FILTER (WHERE anchor_arm_cluster='TREATMENT') n_t_cluster,
                       count(*) FILTER (WHERE anchor_arm_cluster='CONTROL') n_c_cluster,
                       coalesce(sum(passenger_count) FILTER (WHERE anchor_arm_cluster='TREATMENT'),0) w_t_cluster,
                       coalesce(sum(passenger_count) FILTER (WHERE anchor_arm_cluster='CONTROL'),0) w_c_cluster
                FROM card_journey WHERE experiment_id=?
                GROUP BY 1,2,3,4 ORDER BY 1,2,3,4
                """, expId);

        for (Map<String, Object> r : rows) {
            boolean accessible = Boolean.TRUE.equals(r.get("accessibility"));
            double wtT = ((Number) r.get("w_t_trip")).doubleValue();
            double wcT = ((Number) r.get("w_c_trip")).doubleValue();
            double wtC = ((Number) r.get("w_t_cluster")).doubleValue();
            double wcC = ((Number) r.get("w_c_cluster")).doubleValue();

            boolean supportTrip = accessible && wtT >= minW && wcT >= minW;
            boolean supportCluster = accessible && wtC >= minW && wcC >= minW;

            List<String> reasons = new ArrayList<>();
            if (!accessible) reasons.add("服务可达性受损（停运/绕行），无可比服务");
            if (accessible && (wtT < minW || wcT < minW)) {
                reasons.add("班次方案某一臂加权样本不足(" + Math.round(wtT) + "/" + Math.round(wcT) + ")");
            }
            if (accessible && (wtC < minW || wcC < minW)) {
                reasons.add("簇周方案某一臂加权样本不足(" + Math.round(wtC) + "/" + Math.round(wcC) + ")");
            }
            String key = r.get("period_band") + "|" + r.get("od_band") + "|"
                    + r.get("weather_class") + "|" + accessible;

            jdbc.update("""
                    INSERT INTO support_stratum(experiment_id,stratum_key,period_band,od_band,weather_class,
                        accessibility,n_total,weighted_total,n_t_trip,n_c_trip,w_t_trip,w_c_trip,
                        n_t_cluster,n_c_cluster,w_t_cluster,w_c_cluster,
                        supported_trip,supported_cluster,gap_reason)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, expId, key, r.get("period_band"), r.get("od_band"), r.get("weather_class"),
                    accessible, ((Number) r.get("n_total")).intValue(),
                    ((Number) r.get("weighted_total")).doubleValue(),
                    ((Number) r.get("n_t_trip")).intValue(), ((Number) r.get("n_c_trip")).intValue(),
                    wtT, wcT,
                    ((Number) r.get("n_t_cluster")).intValue(), ((Number) r.get("n_c_cluster")).intValue(),
                    wtC, wcC, supportTrip, supportCluster,
                    reasons.isEmpty() ? null : String.join("；", reasons));
        }
        return rows.size();
    }

    // ============================ 估计量 ============================

    public int runAnalysis(long expId) {
        jdbc.update("DELETE FROM analysis_result WHERE experiment_id=?", expId);

        Map<String, Boolean> supportTrip = new LinkedHashMap<>();
        Map<String, Boolean> supportCluster = new LinkedHashMap<>();
        jdbc.queryForList(
                "SELECT stratum_key,supported_trip,supported_cluster FROM support_stratum WHERE experiment_id=?",
                expId).forEach(m -> {
            supportTrip.put(String.valueOf(m.get("stratum_key")), Boolean.TRUE.equals(m.get("supported_trip")));
            supportCluster.put(String.valueOf(m.get("stratum_key")), Boolean.TRUE.equals(m.get("supported_cluster")));
        });

        List<JourneyRow> all = loadAnalysisRows(expId);

        int inserted = 0;
        inserted += estimateDesign(expId, all, supportTrip, true);
        inserted += estimateDesign(expId, all, supportCluster, false);
        inserted += pathSummaries(expId, all);
        inserted += crossExposure(expId, all);
        inserted += clusterRandomizationInference(expId, all, supportCluster);
        return inserted;
    }

    private record JourneyRow(
            long id, String code, String status, String period, String od, String weather,
            boolean accessibility, int pax, String armTrip, String groupTrip,
            String armCluster, String groupCluster, String clusterCode,
            Double waitSec, Double crowd, Double waitCluster, Double crowdCluster,
            String pathLabel, boolean effectiveTreatment, boolean crossLine) {

        String stratumKey() {
            return period + "|" + od + "|" + weather + "|" + accessibility;
        }
    }

    private List<JourneyRow> loadAnalysisRows(long expId) {
        return jdbc.queryForList("""
                SELECT j.id,j.journey_code,j.status,j.period_band,j.od_band,j.weather_class,
                       j.accessibility,j.passenger_count,j.anchor_arm_trip,j.anchor_unit_key,
                       j.anchor_arm_cluster,j.cluster_week_key,j.cluster_code,
                       j.wait_seconds,j.crowd_exposure,j.wait_cluster_seconds,j.crowd_cluster_exposure,
                       e.path_label,e.effective_treatment,e.cross_line
                FROM card_journey j
                LEFT JOIN exposure_ledger e ON e.journey_id = j.id
                WHERE j.experiment_id=?
                """, expId).stream().map(m -> new JourneyRow(
                ((Number) m.get("id")).longValue(),
                String.valueOf(m.get("journey_code")),
                String.valueOf(m.get("status")),
                String.valueOf(m.get("period_band")),
                String.valueOf(m.get("od_band")),
                String.valueOf(m.get("weather_class")),
                Boolean.TRUE.equals(m.get("accessibility")),
                ((Number) m.get("passenger_count")).intValue(),
                (String) m.get("anchor_arm_trip"),
                (String) m.get("anchor_unit_key"),
                (String) m.get("anchor_arm_cluster"),
                (String) m.get("cluster_week_key"),
                (String) m.get("cluster_code"),
                dbl(m.get("wait_seconds")),
                dbl(m.get("crowd_exposure")),
                dbl(m.get("wait_cluster_seconds")),
                dbl(m.get("crowd_cluster_exposure")),
                m.get("path_label") == null ? null : String.valueOf(m.get("path_label")),
                Boolean.TRUE.equals(m.get("effective_treatment")),
                Boolean.TRUE.equals(m.get("cross_line"))
        )).toList();
    }

    private static Double dbl(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    private int estimateDesign(long expId, List<JourneyRow> all, Map<String, Boolean> support, boolean trip) {
        String design = trip ? "TRIP_RANDOMIZED" : "CLUSTER_WEEK";
        int count = 0;

        // 主要口径：共同支持域内的完整旅程
        for (String metric : List.of("wait_seconds", "crowd_exposure")) {
            List<Stats.Observation> obs = new ArrayList<>();
            for (JourneyRow r : all) {
                if (!"COMPLETE".equals(r.status()) || !Boolean.TRUE.equals(support.get(r.stratumKey()))) continue;
                Double y = trip ? ("wait_seconds".equals(metric) ? r.waitSec() : r.crowd())
                        : ("wait_seconds".equals(metric) ? r.waitCluster() : r.crowdCluster());
                String arm = trip ? r.armTrip() : r.armCluster();
                String group = trip ? r.groupTrip() : r.groupCluster();
                if (y == null || arm == null || group == null) continue;
                obs.add(new Stats.Observation(y, r.pax(), "TREATMENT".equals(arm) ? 1 : 0, group));
            }
            if (obs.size() < 4) continue;
            Stats.EffectEstimate fit = Stats.clusterITT(obs);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("weighting", "passenger_count 加权 Hajek");
            detail.put("variance", trip
                    ? "按线路—班次随机化单元聚类 CR1"
                    : "按簇—周随机化单元聚类 CR1, df=G-1");
            detail.put("supportedStrataOnly", true);
            detail.put("interpretation", "意向处理效应 ITT：按随机分配臂比较，保留不执行者与污染路径在其分配臂内");

            if (!trip && "wait_seconds".equals(metric)) {
                detail.put("fewClustersWarning",
                        "全网只有 2 个线路簇：按簇本身做 G=2 聚类稳健方差会退化/不稳定，"
                                + "故主区间采用簇—周随机化单元 CR1(df=7)，并以下方 RI_PERMUTATION "
                                + "的随机化推断 p 值作为小簇数下的主要不确定性证据；CI 更宽即源于有效单元少。");
            }

            saveResult(expId, design, "ITT", metric, "COMMON_SUPPORT", fit, Json.write(detail));
            count++;

            // 全合格旅程口径（不做支持层筛选），仅作覆盖对照
            List<Stats.Observation> allObs = new ArrayList<>();
            for (JourneyRow r : all) {
                if (!"COMPLETE".equals(r.status())) continue;
                Double y = trip ? ("wait_seconds".equals(metric) ? r.waitSec() : r.crowd())
                        : ("wait_seconds".equals(metric) ? r.waitCluster() : r.crowdCluster());
                String arm = trip ? r.armTrip() : r.armCluster();
                String group = trip ? r.groupTrip() : r.groupCluster();
                if (y == null || arm == null || group == null) continue;
                allObs.add(new Stats.Observation(y, r.pax(), "TREATMENT".equals(arm) ? 1 : 0, group));
            }
            Stats.EffectEstimate allFit = Stats.clusterITT(allObs);
            saveResult(expId, design, "ITT", metric, "ALL_ELIGIBLE", allFit,
                    Json.write(Map.of("supportedStrataOnly", false,
                            "note", "包含无共同支持旅程，仅作总体描述，不能替代共同支持因果口径")));
            count++;
        }

        // CACE / Wald（班次方案，候车指标）：分配臂作为“全程实际接受新规则”的工具变量
        if (trip) {
            List<Stats.Observation> yo = new ArrayList<>();
            List<Stats.Observation> dO = new ArrayList<>();
            for (JourneyRow r : all) {
                if (!"COMPLETE".equals(r.status()) || !Boolean.TRUE.equals(support.get(r.stratumKey()))) continue;
                if (r.waitSec() == null || r.armTrip() == null || r.groupTrip() == null) continue;
                int a = "TREATMENT".equals(r.armTrip()) ? 1 : 0;
                yo.add(new Stats.Observation(r.waitSec(), r.pax(), a, r.groupTrip()));
                dO.add(new Stats.Observation(r.effectiveTreatment() ? 1 : 0, r.pax(), a, r.groupTrip()));
            }
            double[] z = Stats.clusterCovariancePair(yo, dO);
            int groups = (int) yo.stream().map(Stats.Observation::group).distinct().count();
            double tcrit = Stats.tCritical(Math.max(1, groups - 1));
            double wald = z[5], seW = z[6];
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reducedFormITT", r2(z[0]));
            detail.put("firstStage", r3(z[1]));
            detail.put("method", "Wald/IV delta-method 聚类 SE");
            detail.put("warning", "CACE/LATE 仅在排他性等工具变量假设下成立；"
                    + "按实际执行者直接分组的均值比较是观察性关联，不是随机效应");
            jdbc.update("""
                    INSERT INTO analysis_result(experiment_id,design_type,estimator_code,metric_code,scope,
                        estimate,se,ci_low,ci_high,p_value,n_journeys,n_units,detail)
                    VALUES (?,?, 'CACE_WALD','wait_seconds','COMMON_SUPPORT',?,?,?,?,?,?,?,?::jsonb)
                    """, expId, design, r2(wald), r2(seW), r2(wald - tcrit * seW), r2(wald + tcrit * seW),
                    Stats.normalTwoSidedP(Math.abs(wald / seW)), yo.size(), groups, Json.write(detail));
            count++;

            // 明确标注为非随机的 as-treated 关联
            for (String label : List.of("FULL_TREATMENT", "FULL_CONTROL")) {
                List<Double> ys = new ArrayList<>();
                List<Double> ws = new ArrayList<>();
                for (JourneyRow r : all) {
                    if ("COMPLETE".equals(r.status()) && label.equals(r.pathLabel()) && r.waitSec() != null) {
                        ys.add(r.waitSec()); ws.add((double) r.pax());
                    }
                }
                if (ys.size() < 10) continue;
                double mean = Stats.weightedMean(ys, ws);
                double se = Stats.weightedMeanSe(ys, ws, mean);
                jdbc.update("""
                        INSERT INTO analysis_result(experiment_id,design_type,estimator_code,metric_code,scope,
                            estimate,se,ci_low,ci_high,p_value,n_journeys,n_units,detail)
                        VALUES (?,?, 'AS_TREATED_ASSOCIATION','wait_seconds',?,?,?,?,?,?,?,?,?::jsonb)
                        """, expId, design, "ACTUAL_PATH:" + label, r2(mean), r2(se),
                        r2(mean - 1.96 * se), r2(mean + 1.96 * se), null, ys.size(), 0,
                        Json.write(Map.of("warning", "实际暴露子集的均值，观察性关联，不得称为随机效应",
                                "pathLabel", label)));
                count++;
            }
        }
        return count;
    }

    private void saveResult(long expId, String design, String estimator, String metric, String scope,
                            Stats.EffectEstimate fit, String detail) {
        jdbc.update("""
                INSERT INTO analysis_result(experiment_id,design_type,estimator_code,metric_code,scope,
                    estimate,se,ci_low,ci_high,p_value,n_journeys,n_units,detail)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                """, expId, design, estimator, metric, scope,
                r2(fit.estimate()), r2(fit.se()), r2(fit.ciLow()), r2(fit.ciHigh()),
                r4(fit.pValue()), fit.n(), fit.groups(), detail);
    }

    private int pathSummaries(long expId, List<JourneyRow> all) {
        int n = 0;
        for (String label : List.of("FULL_TREATMENT", "FULL_CONTROL", "CROSS_MIXED")) {
            for (String metric : List.of("wait_seconds", "crowd_exposure")) {
                List<Double> vals = new ArrayList<>();
                List<Double> ws = new ArrayList<>();
                for (JourneyRow r : all) {
                    if (!"COMPLETE".equals(r.status()) || !label.equals(r.pathLabel())) continue;
                    Double y = "wait_seconds".equals(metric) ? r.waitSec() : r.crowd();
                    if (y == null) continue;
                    vals.add(y);
                    ws.add((double) r.pax());
                }
                if (vals.size() < 5) continue;
                double mean = Stats.weightedMean(vals, ws);
                double se = Stats.weightedMeanSe(vals, ws, mean);
                jdbc.update("""
                        INSERT INTO analysis_result(experiment_id,design_type,estimator_code,metric_code,scope,
                            estimate,se,ci_low,ci_high,p_value,n_journeys,n_units,detail)
                        VALUES (?,'TRIP_RANDOMIZED','PATH_MEAN',?,?,?,?,?, ?,NULL,?,0,?::jsonb)
                        """, expId, metric, "PATH:" + label, r2(mean), r2(se),
                        r2(mean - 1.96 * se), r2(mean + 1.96 * se), vals.size(),
                        Json.write(Map.of("pathLabel", label, "weighted", true,
                                "note", "完整路径暴露组的加权描述均值；CROSS_MIXED 不进入 FULL_CONTROL")));
                n++;
            }
        }
        return n;
    }

    private int crossExposure(long expId, List<JourneyRow> all) {
        long complete = all.stream().filter(r -> "COMPLETE".equals(r.status())).count();
        long cross = all.stream().filter(JourneyRow::crossLine).count();
        long unknown = all.stream().filter(r -> "UNKNOWN".equals(r.pathLabel())
                || !"COMPLETE".equals(r.status())).count();
        double weightedCross = all.stream().filter(JourneyRow::crossLine)
                .mapToInt(JourneyRow::pax).sum();
        double weightedAll = all.stream().mapToInt(JourneyRow::pax).sum();
        double[] w = Stats.wilson(cross, complete, 1.96);

        jdbc.update("""
                INSERT INTO analysis_result(experiment_id,design_type,estimator_code,metric_code,scope,
                    estimate,se,ci_low,ci_high,p_value,n_journeys,n_units,detail)
                VALUES (?,'TRIP_RANDOMIZED','CROSS_RATE','cross_exposure_rate','ALL_ELIGIBLE',
                    ?,NULL,?,?,NULL,?,0,?::jsonb)
                """, expId, r4(w[0]), r4(w[1]), r4(w[2]), complete,
                Json.write(Map.of(
                        "crossJourneys", cross,
                        "completeJourneys", complete,
                        "unknownJourneys", unknown,
                        "weightedCrossShare", r4(weightedCross / weightedAll),
                        "ciMethod", "Wilson 95%",
                        "note", "共享枢纽异臂搭乘/分流的完整路径占比；交叉暴露单独成类")));
        return 1;
    }

    private int clusterRandomizationInference(long expId, List<JourneyRow> all,
                                              Map<String, Boolean> supportCluster) {
        List<String> clusterCodes = jdbc.queryForList(
                        "SELECT cluster_code FROM route_cluster WHERE experiment_id=? ORDER BY cluster_code",
                        String.class, expId);
        if (clusterCodes.size() != 2) return 0;
        String c0 = clusterCodes.get(0);

        List<Stats.RiRow> rows = new ArrayList<>();
        for (JourneyRow r : all) {
            if (!"COMPLETE".equals(r.status()) || !Boolean.TRUE.equals(supportCluster.get(r.stratumKey()))) continue;
            if (r.waitCluster() == null || r.clusterCode() == null) continue;
            int ci = r.clusterCode().equals(c0) ? 0 : 1;
            // week from group key CW:CODE:Wn
            try {
                int week = Integer.parseInt(r.groupCluster().substring(r.groupCluster().lastIndexOf('W') + 1)) - 1;
                rows.add(new Stats.RiRow(week, ci, r.waitCluster(), r.pax()));
            } catch (Exception ignored) {
                // 跳过无法解析周次的行
            }
        }
        if (rows.size() < 10) return 0;

        // 观测方向
        int[] observed = new int[4];
        for (int wk = 1; wk <= 4; wk++) {
            String key0 = "CW:" + c0 + ":W" + wk;
            String arm = jdbc.queryForObject(
                    "SELECT arm FROM assignment_unit WHERE experiment_id=? AND unit_key=? AND design_type='CLUSTER_WEEK'",
                    String.class, expId, key0);
            observed[wk - 1] = "TREATMENT".equals(arm) ? 0 : 1;
        }
        List<int[]> perms = new ArrayList<>();
        for (int mask = 0; mask < 16; mask++) {
            int[] ori = new int[4];
            for (int w = 0; w < 4; w++) ori[w] = (mask >> w) & 1;
            perms.add(ori);
        }
        double[] ri = Stats.randomizationInferenceClusterWeeks(rows, perms, observed);

        jdbc.update("""
                INSERT INTO analysis_result(experiment_id,design_type,estimator_code,metric_code,scope,
                    estimate,se,ci_low,ci_high,p_value,n_journeys,n_units,detail)
                VALUES (?,'CLUSTER_WEEK','RI_PERMUTATION','wait_seconds','COMMON_SUPPORT',
                    ?,NULL,NULL,NULL,?, ?,8,?::jsonb)
                """, expId, r2(ri[1]), r4(ri[0]), rows.size(),
                Json.write(Map.of(
                        "permutations", 16,
                        "method", "枚举每周两簇方向的全部 2^4=16 种随机化分配",
                        "pValueFormula", "(1+#|perm|>=|obs|)/17",
                        "note", "只有 2 个簇时推荐报告 RI p 值而非渐近 CI")));
        return 1;
    }

    // ============================ 护栏 ============================

    public List<Map<String, Object>> evaluateGuardrails(long expId) {
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT * FROM guardrail_rule WHERE experiment_id=? AND enabled=TRUE ORDER BY id", expId);
        List<Map<String, Object>> evals = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            String code = String.valueOf(rule.get("code"));
            double threshold = ((Number) rule.get("threshold")).doubleValue();
            int minSample = ((Number) rule.get("min_sample")).intValue();

            double observed;
            int sampleN;
            Map<String, Object> detail = new LinkedHashMap<>();

            switch (String.valueOf(rule.get("metric_source"))) {
                case "complaint_rate_per_10k" -> {
                    Long complaints = jdbc.queryForObject(
                            "SELECT count(*) FROM complaint WHERE experiment_id=?", Long.class, expId);
                    Double pax = jdbc.queryForObject(
                            "SELECT coalesce(sum(passenger_count),0) FROM card_journey WHERE experiment_id=?",
                            Double.class, expId);
                    long c = complaints == null ? 0 : complaints;
                    double p = pax == null || pax == 0 ? 0 : pax;
                    observed = p == 0 ? 0 : c / p * 10000;
                    sampleN = (int) c;

                    Long lagged = jdbc.queryForObject("""
                            SELECT count(*) FROM complaint WHERE experiment_id=?
                            AND complaint_time::date <> service_event_time::date
                            """, Long.class, expId);
                    Double maxLag = jdbc.queryForObject("""
                            SELECT max(extract(day FROM complaint_time - service_event_time))
                            FROM complaint WHERE experiment_id=?
                            """, Double.class, expId);
                    detail.put("complaints", c);
                    detail.put("passengerJourneysWeighted", p);
                    detail.put("timeBasis", "service_event_time（实际服务发生时间），不是 complaint_time");
                    detail.put("complaintsLoggedOnLaterDate", lagged);
                    detail.put("maxReportLagDays", maxLag == null ? 0 : maxLag.intValue());
                    detail.put("timeAssociationCheck",
                            "全部投诉按 service_event_time 归入试点服务日期；投诉提交滞后不改变归属");

                    List<Map<String, Object>> byArm = jdbc.queryForList("""
                            SELECT j.anchor_arm_trip arm, count(c.*) complaints,
                                   coalesce(sum(j.passenger_count),0) pax
                            FROM complaint c JOIN card_journey j ON j.id = c.journey_id
                            WHERE c.experiment_id=? GROUP BY 1
                            """, expId);
                    detail.put("byAssignedArm", byArm);
                }
                case "overload_share" -> {
                    Long over = jdbc.queryForObject("""
                            SELECT count(*) FROM actual_service_run r
                            JOIN service_trip t ON t.id=r.trip_id
                            WHERE t.experiment_id=? AND r.observed_lf >= 1.0
                            """, Long.class, expId);
                    Long denom = jdbc.queryForObject("""
                            SELECT count(*) FROM actual_service_run r
                            JOIN service_trip t ON t.id=r.trip_id
                            WHERE t.experiment_id=? AND r.observed_lf IS NOT NULL
                            """, Long.class, expId);
                    int o = over == null ? 0 : over.intValue();
                    int d = denom == null ? 0 : denom.intValue();
                    observed = d == 0 ? 0 : o / (double) d;
                    sampleN = d;
                    detail.put("overloadedRuns", o);
                    detail.put("observedRuns", d);
                    detail.put("source", "GPS/AVL 班次满载观测 + load_count");
                    List<Map<String, Object>> top = jdbc.queryForList("""
                            SELECT t.trip_code, t.route_code, r.observed_lf, r.deviation
                            FROM actual_service_run r JOIN service_trip t ON t.id=r.trip_id
                            WHERE t.experiment_id=? AND r.observed_lf IS NOT NULL
                            ORDER BY r.observed_lf DESC LIMIT 5
                            """, expId);
                    detail.put("topLoadedRuns", top);
                }
                default -> { // stranding_rate
                    Long total = jdbc.queryForObject(
                            "SELECT count(*) FROM card_journey WHERE experiment_id=?", Long.class, expId);
                    Long bad = jdbc.queryForObject("""
                            SELECT count(*) FROM card_journey WHERE experiment_id=?
                            AND (status <> 'COMPLETE' OR wait_seconds > 1200)
                            """, Long.class, expId);
                    int tt = total == null ? 0 : total.intValue();
                    int bb = bad == null ? 0 : bad.intValue();
                    observed = tt == 0 ? 0 : bb / (double) tt;
                    sampleN = tt;
                    detail.put("strandedOrUnknown", bb);
                    detail.put("totalJourneys", tt);
                    detail.put("rule", "status<>COMPLETE（未完成刷卡/中途改线）或端到端候车>1200秒");
                }
            }

            boolean enough = sampleN >= minSample;
            boolean breached = enough && ("GT".equals(rule.get("comparator"))
                    ? observed > threshold : observed < threshold);
            String action = !enough ? "INSUFFICIENT_EVIDENCE" : breached ? "STOP_EXPANSION" : "OK";

            jdbc.update("""
                    INSERT INTO guardrail_evaluation(experiment_id,rule_code,observed,threshold,sample_n,
                        breached,action,detail,evaluated_at)
                    VALUES (?,?,?,?,?,?,?,?::jsonb, now())
                    """, expId, code, r4(observed), threshold, sampleN, breached, action, Json.write(detail));

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("ruleCode", code);
            ev.put("ruleName", rule.get("name"));
            ev.put("metricSource", rule.get("metric_source"));
            ev.put("observed", r4(observed));
            ev.put("threshold", threshold);
            ev.put("comparator", rule.get("comparator"));
            ev.put("sampleN", sampleN);
            ev.put("minSample", minSample);
            ev.put("breached", breached);
            ev.put("action", action);
            ev.put("detail", detail);
            evals.add(ev);
        }
        return evals;
    }

    private static double r2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 100.0) / 100.0;
    }

    private static double r3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double r4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
