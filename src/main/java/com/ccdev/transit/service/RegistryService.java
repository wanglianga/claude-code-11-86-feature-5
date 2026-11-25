package com.ccdev.transit.service;

import com.ccdev.transit.engine.AssignmentEngine;
import com.ccdev.transit.engine.Deterministic;
import com.ccdev.transit.engine.NetworkClusterer;
import com.ccdev.transit.engine.TripTemplates;
import com.ccdev.transit.util.Json;
import com.ccdev.transit.web.dto.ExperimentRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实验登记与冻结：合格线路、处理规则、种子、概率、目标人群、指标、周期、
 * 实验前网络簇与两套随机分配，一次性写入并计算登记指纹。
 */
@Service
public class RegistryService {

    private final JdbcTemplate jdbc;
    private final AssignmentEngine assignmentEngine = new AssignmentEngine();
    private final NetworkClusterer clusterer = new NetworkClusterer();

    public RegistryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createDraft(ExperimentRequest req) {
        Double p = req.assignmentProb() == null ? 0.5 : req.assignmentProb();
        int weeks = req.pilotWeeks() == null ? 4 : req.pilotWeeks();
        LocalDate start = req.startDate() == null ? LocalDate.of(2026, 6, 1) : LocalDate.parse(req.startDate());

        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("headway_compression_peak_seconds", 120);
        rules.put("short_turn_enabled", true);
        rules.put("short_turn_rule", "高峰断面客流超过阈值时在指定折返站短线折返，折返前完成清客播报");
        if (req.treatmentRules() != null && !req.treatmentRules().isBlank()) {
            rules.put("custom", req.treatmentRules());
        }
        Map<String, Object> primary = Map.of(
                "wait_seconds", "每次完整旅程端到端候车时间（首乘+换乘等待之和，秒）",
                "crowd_exposure", "拥挤暴露 Σ 满载系数×在车分钟");
        Map<String, Object> safety = Map.of(
                "complaint_rate_per_10k", "按服务发生时间归集的每万乘客投诉率",
                "overload_share", "满载系数≥1.0 的实际运行班次占比",
                "stranding_rate", "未完成刷卡/中途改线或候车>1200 秒的旅程占比");
        Map<String, Object> netCfg = Map.of("edge_ratio_threshold",
                req.edgeThreshold() == null ? 0.15 : req.edgeThreshold());
        Map<String, Object> anaCfg = Map.of("min_weighted_per_arm",
                req.minSupportPerArm() == null ? 25 : req.minSupportPerArm());

        jdbc.update("""
                INSERT INTO experiment(code,name,status,active_design,seed,assignment_prob,saturation,
                    pilot_weeks,start_date,end_date,target_population,treatment_rules,
                    primary_metrics,safety_metrics,network_config,analysis_config)
                VALUES (?,?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)
                """,
                req.code(), req.name(),
                req.activeDesign() == null ? "TRIP_RANDOMIZED" : req.activeDesign(),
                req.seed() == null ? "SEED-" + req.code() : req.seed(),
                p, p, weeks, java.sql.Date.valueOf(start), java.sql.Date.valueOf(start.plusDays(7L * weeks - 1)),
                req.targetPopulation() == null
                        ? "试点网络内合格线路上、试点日期内发生的完整刷卡旅程（含首乘与换乘）；未完成刷卡/中途改线保留未知"
                        : req.targetPopulation(),
                Json.write(rules), Json.write(primary), Json.write(safety),
                Json.write(netCfg), Json.write(anaCfg));

        Long id = jdbc.queryForObject("SELECT id FROM experiment WHERE code = ?", Long.class, req.code());

        if (req.routes() != null) {
            for (ExperimentRequest.RouteDto r : req.routes()) {
                jdbc.update("INSERT INTO route(experiment_id,code,name) VALUES (?,?,?) "
                                + "ON CONFLICT (experiment_id,code) DO NOTHING",
                        id, r.code(), r.name() == null ? r.code() + "路" : r.name());
            }
        }
        if (req.preEdges() != null) {
            for (ExperimentRequest.EdgeDto e : req.preEdges()) {
                int tt = e.totalJourneys() == null ? 0 : e.totalJourneys();
                int tj = e.transferJourneys() == null ? 0 : e.transferJourneys();
                double ratio = tt == 0 ? 0 : tj / (double) tt;
                jdbc.update("""
                        INSERT INTO pre_transfer_edge(experiment_id,from_route,to_route,
                            transfer_journeys,total_journeys,ratio,frozen)
                        VALUES (?,?,?,?,?,?,TRUE)
                        ON CONFLICT (experiment_id,from_route,to_route) DO NOTHING
                        """, id, e.fromRoute(), e.toRoute(), tj, tt, ratio);
            }
        }
        return id;
    }

    /** 冻结网络簇并生成两套 50% 平衡分配；幂等：已登记则直接返回。 */
    @Transactional
    public String register(long experimentId) {
        Map<String, Object> exp = jdbc.queryForMap("SELECT * FROM experiment WHERE id = ?", experimentId);
        if ("REGISTERED".equals(exp.get("status")) || hasAssignments(experimentId)) {
            return String.valueOf(exp.get("registration_hash"));
        }

        double p = ((Number) exp.get("assignment_prob")).doubleValue();
        String seed = String.valueOf(exp.get("seed"));
        int weeks = ((Number) exp.get("pilot_weeks")).intValue();
        double threshold = readThreshold(String.valueOf(exp.get("network_config")));

        List<String> routeCodes = jdbc.queryForList(
                "SELECT code FROM route WHERE experiment_id = ? ORDER BY code", String.class, experimentId);
        List<NetworkClusterer.TransferEdge> edges = jdbc.queryForList(
                        "SELECT from_route,to_route,ratio FROM pre_transfer_edge WHERE experiment_id = ?",
                        experimentId).stream()
                .map(m -> new NetworkClusterer.TransferEdge(
                        String.valueOf(m.get("from_route")),
                        String.valueOf(m.get("to_route")),
                        ((Number) m.get("ratio")).doubleValue()))
                .toList();

        NetworkClusterer.ClusterPlan plan = clusterer.cluster(routeCodes, edges, threshold);

        // 簇编码：C-R1R2 / C-R3，可读性优先
        List<String> clusterCodes = new ArrayList<>();
        for (Set<String> cluster : plan.clusters()) {
            String cc = "C-" + String.join("", cluster);
            clusterCodes.add(cc);
            jdbc.update("""
                    INSERT INTO route_cluster(experiment_id,cluster_code,route_codes)
                    VALUES (?,?,?) ON CONFLICT (experiment_id,cluster_code) DO NOTHING
                    """, experimentId, cc, String.join(",", cluster));
        }

        // ---- 方案一：线路—具体班次（日期级）单元，层=线路×日期×时段，每层 4 班严格 2/2 ----
        LocalDate pilotStart = ((java.sql.Date) exp.get("start_date")).toLocalDate();
        List<LocalDate> weekdays = new ArrayList<>();
        for (int w = 0; w < weeks; w++) {
            for (int dow = 0; dow < 5; dow++) {
                weekdays.add(pilotStart.plusDays((long) w * 7 + dow));
            }
        }
        List<AssignmentEngine.UnitSpec> tripUnits = new ArrayList<>();
        for (String route : routeCodes) {
            for (LocalDate date : weekdays) {
                for (TripTemplates.TemplateSpec t : TripTemplates.ALL) {
                    String key = "TRIP:" + route + ":" + date + ":" + t.code();
                    tripUnits.add(new AssignmentEngine.UnitSpec(
                            key, "BAND:" + route + ":" + date + ":" + t.band()));
                }
            }
        }
        List<AssignmentEngine.Assignment> tripAssignments =
                assignmentEngine.assignBalanced(seed + "#TRIP", p, tripUnits);
        for (AssignmentEngine.Assignment a : tripAssignments) {
            // TRIP:R1:2026-06-01:AM1
            String[] parts = a.unitKey().split(":");
            String route = parts.length > 1 ? parts[1] : "";
            String label = route + " " + (parts.length > 2 ? parts[2] : "")
                    + " " + (parts.length > 3 ? parts[3] : "");
            jdbc.update("""
                    INSERT INTO assignment_unit(experiment_id,design_type,unit_key,stratum,label,
                        route_codes,cluster_code,week_index,hash_value,arm,inclusion_prob)
                    VALUES (?,'TRIP_RANDOMIZED',?,?,?,?,NULL,NULL,?,?,?)
                    ON CONFLICT (experiment_id,design_type,unit_key) DO NOTHING
                    """, experimentId, a.unitKey(), a.stratum(), label, route,
                    a.hash(), a.arm(), a.inclusionProb());
        }

        // ---- 方案二：簇—周单元，层=周，每周 2 簇时严格 1/1 ----
        List<AssignmentEngine.UnitSpec> cwUnits = new ArrayList<>();
        for (int w = 1; w <= weeks; w++) {
            for (String cc : clusterCodes) {
                cwUnits.add(new AssignmentEngine.UnitSpec(
                        "CW:" + cc + ":W" + w, "WEEK:" + w));
            }
        }
        List<AssignmentEngine.Assignment> cwAssignments =
                assignmentEngine.assignBalanced(seed + "#CW", p, cwUnits);

        // 补充 cluster_code / week_index / route_codes
        Map<String, String> clusterRoutes = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT cluster_code, route_codes FROM route_cluster WHERE experiment_id = ?", experimentId)) {
            clusterRoutes.put(String.valueOf(row.get("cluster_code")), String.valueOf(row.get("route_codes")));
        }
        for (AssignmentEngine.Assignment a : cwAssignments) {
            String key = a.unitKey(); // CW:C-R1R2:W1
            String cc = key.substring(3, key.lastIndexOf(":W"));
            int w = Integer.parseInt(key.substring(key.lastIndexOf(":W") + 2));
            jdbc.update("""
                    INSERT INTO assignment_unit(experiment_id,design_type,unit_key,stratum,label,
                        route_codes,cluster_code,week_index,hash_value,arm,inclusion_prob)
                    VALUES (?,'CLUSTER_WEEK',?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (experiment_id,design_type,unit_key) DO NOTHING
                    """, experimentId, key, a.stratum(), cc + " 第" + w + "周",
                    clusterRoutes.get(cc), cc, w, a.hash(), a.arm(), a.inclusionProb());
        }

        String manifestTrip = AssignmentEngine.manifestHash(tripAssignments);
        String manifestCw = AssignmentEngine.manifestHash(cwAssignments);
        String regHash = Deterministic.sha256Hex(
                exp.get("code") + "|" + seed + "|" + p + "|" + weeks + "|"
                        + threshold + "|" + edges.size() + "|" + manifestTrip + "|" + manifestCw);

        jdbc.update("""
                UPDATE experiment SET status='REGISTERED', registration_hash=?, registered_at=?
                WHERE id=?
                """, regHash, new Timestamp(System.currentTimeMillis()), experimentId);
        return regHash;
    }

    private void persistAssignments(long experimentId, String design,
                                   List<AssignmentEngine.Assignment> assignments,
                                   String clusterCode, Integer weekIndex) {
        for (AssignmentEngine.Assignment a : assignments) {
            String label = a.unitKey();
            String routes = "";
            if ("TRIP_RANDOMIZED".equals(design)) {
                // TRIP:R1:AM2
                String[] parts = a.unitKey().split(":");
                routes = parts.length > 1 ? parts[1] : "";
                label = routes + " 班次 " + (parts.length > 2 ? parts[2] : "");
            }
            jdbc.update("""
                    INSERT INTO assignment_unit(experiment_id,design_type,unit_key,stratum,label,
                        route_codes,cluster_code,week_index,hash_value,arm,inclusion_prob)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (experiment_id,design_type,unit_key) DO NOTHING
                    """, experimentId, design, a.unitKey(), a.stratum(), label,
                    routes, clusterCode, weekIndex, a.hash(), a.arm(), a.inclusionProb());
        }
    }

    private boolean hasAssignments(long experimentId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM assignment_unit WHERE experiment_id = ?", Integer.class, experimentId);
        return n != null && n > 0;
    }

    @SuppressWarnings("unchecked")
    private double readThreshold(String networkConfigJson) {
        try {
            Map<String, Object> cfg = Json.read(networkConfigJson, Map.class);
            Object v = cfg.get("edge_ratio_threshold");
            return v == null ? 0.15 : ((Number) v).doubleValue();
        } catch (Exception e) {
            return 0.15;
        }
    }
}
