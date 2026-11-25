package com.ccdev.transit.web;

import com.ccdev.transit.service.CostService;
import com.ccdev.transit.service.ExpansionService;
import com.ccdev.transit.service.PipelineService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/experiments/{id}")
public class PipelineController {

    private final JdbcTemplate jdbc;
    private final PipelineService pipeline;
    private final ExpansionService expansion;
    private final CostService costService;

    public PipelineController(JdbcTemplate jdbc, PipelineService pipeline,
                              ExpansionService expansion, CostService costService) {
        this.jdbc = jdbc;
        this.pipeline = pipeline;
        this.expansion = expansion;
        this.costService = costService;
    }

    // ---------------- 流水线 ----------------

    @PostMapping("/pipeline/run")
    public Map<String, Object> runPipeline(@PathVariable long id) {
        pipeline.rebuildAll(id);
        costService.recompute(id);
        return Map.of("ok", true, "message",
                "暴露账、共同支持、分析结果、护栏与试点成本归集已用冻结数据重新计算");
    }

    @PostMapping("/analysis/run")
    public Map<String, Object> runAnalysis(@PathVariable long id) {
        int n = pipeline.runAnalysis(id);
        return Map.of("ok", true, "resultRows", n);
    }

    // ---------------- 暴露路径 ----------------

    @GetMapping("/exposure")
    public Map<String, Object> exposure(@PathVariable long id,
                                        @RequestParam(required = false) String pathLabel,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "100") int limit) {
        StringBuilder where = new StringBuilder(" WHERE e.experiment_id=? ");
        List<Object> args = new java.util.ArrayList<>();
        args.add(id);
        if (pathLabel != null && !pathLabel.isBlank() && !"ALL".equals(pathLabel)) {
            where.append(" AND e.path_label=? ");
            args.add(pathLabel);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (e.journey_code ILIKE ? OR j.note ILIKE ?) ");
            args.add("%" + q + "%");
            args.add("%" + q + "%");
        }
        args.add(Math.min(500, limit));

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.journey_id, e.journey_code, e.status, e.path_label, e.cluster_path_label,
                       e.treatment_legs, e.control_legs, e.cross_legs, e.unknown_legs,
                       e.assigned_arm_trip, e.assigned_arm_cluster, e.effective_treatment,
                       e.cross_line, e.diverted, e.path_probability, e.path_detail::text AS path_detail,
                       j.note, j.wait_seconds, j.crowd_exposure, j.passenger_count
                FROM exposure_ledger e JOIN card_journey j ON j.id=e.journey_id
                """ + where + " ORDER BY e.journey_code LIMIT ?", args.toArray());

        List<Map<String, Object>> counts = jdbc.queryForList("""
                SELECT path_label, count(*) n, coalesce(sum(passenger_count),0) weighted_n
                FROM exposure_ledger e JOIN card_journey j ON j.id=e.journey_id
                WHERE e.experiment_id=? GROUP BY path_label ORDER BY path_label
                """, id);
        List<Map<String, Object>> clusterCounts = jdbc.queryForList("""
                SELECT cluster_path_label, count(*) n
                FROM exposure_ledger WHERE experiment_id=? GROUP BY cluster_path_label
                ORDER BY cluster_path_label
                """, id);

        return Map.of("rows", rows, "counts", counts, "clusterCounts", clusterCounts);
    }

    @GetMapping("/journeys/{code}")
    public Map<String, Object> journey(@PathVariable long id, @PathVariable String code) {
        Map<String, Object> j = jdbc.queryForMap(
                "SELECT * FROM card_journey WHERE experiment_id=? AND journey_code=?", id, code);
        List<Map<String, Object>> legs = jdbc.queryForList("""
                SELECT l.leg_index,l.route_code,l.board_stop,l.alight_stop,l.is_transfer,
                       l.unit_key,l.planned_arm,l.rule_effective,l.deviation,l.leg_exposure,
                       l.wait_seconds,l.ride_minutes,l.load_factor,
                       r.rule_executed AS run_rule_executed, r.deviation AS run_deviation,
                       r.gps_run_code, r.headway_seconds, r.delay_seconds
                FROM journey_leg l
                LEFT JOIN actual_service_run r ON r.id=l.actual_run_id
                WHERE l.journey_id=(SELECT id FROM card_journey WHERE experiment_id=? AND journey_code=?)
                ORDER BY l.leg_index
                """, id, code);
        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT *, path_detail::text AS path_detail_text FROM exposure_ledger "
                        + "WHERE experiment_id=? AND journey_code=?", id, code);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("journey", j);
        m.put("legs", legs);
        m.put("ledger", ledger.isEmpty() ? null : ledger.get(0));
        return m;
    }

    // ---------------- 共同支持 ----------------

    @GetMapping("/support")
    public Map<String, Object> support(@PathVariable long id) {
        List<Map<String, Object>> strata = jdbc.queryForList("""
                SELECT * FROM support_stratum WHERE experiment_id=?
                ORDER BY supported_trip, supported_cluster, period_band, od_band, weather_class
                """, id);
        Map<String, Object> gaps = jdbc.queryForMap("""
                SELECT
                  count(*) FILTER (WHERE NOT supported_trip) AS gap_strata_trip,
                  count(*) FILTER (WHERE NOT supported_cluster) AS gap_strata_cluster,
                  coalesce(sum(n_total) FILTER (WHERE NOT supported_trip),0) AS gap_journeys_trip,
                  coalesce(sum(n_total) FILTER (WHERE NOT supported_cluster),0) AS gap_journeys_cluster,
                  coalesce(sum(n_total),0) AS total_stratum_journeys
                FROM support_stratum WHERE experiment_id=?
                """, id);
        return Map.of("strata", strata, "gaps", gaps,
                "policy", "无共同支持的旅程只报告覆盖缺口，不向外推总体因果效应");
    }

    // ---------------- 分析结果 ----------------

    @GetMapping("/analysis")
    public Map<String, Object> analysis(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,design_type,estimator_code,metric_code,scope,estimate,se,ci_low,ci_high,
                       p_value,n_journeys,n_units,detail,created_at
                FROM analysis_result WHERE experiment_id=?
                ORDER BY design_type, metric_code, estimator_code, scope
                """, id);
        return Map.of("results", rows);
    }

    // ---------------- 护栏 ----------------

    @GetMapping("/guardrails")
    public Map<String, Object> guardrails(@PathVariable long id) {
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT * FROM guardrail_rule WHERE experiment_id=? ORDER BY id", id);
        List<Map<String, Object>> latest = jdbc.queryForList("""
                SELECT ge.* FROM guardrail_evaluation ge
                WHERE ge.experiment_id=? AND ge.id IN (
                    SELECT max(id) FROM guardrail_evaluation WHERE experiment_id=? GROUP BY rule_code)
                ORDER BY ge.rule_code
                """, id, id);
        return Map.of("rules", rules, "latest", latest);
    }

    @PostMapping("/guardrails/evaluate")
    public Map<String, Object> evaluateGuardrails(@PathVariable long id) {
        return Map.of("evaluations", pipeline.evaluateGuardrails(id));
    }

    // ---------------- 扩围 ----------------

    @GetMapping("/expansion")
    public Map<String, Object> expansionInfo(@PathVariable long id) {
        return Map.of("latest", expansion.latestDecision(id), "history", expansion.decisions(id));
    }

    @PostMapping("/expansion/evaluate")
    public Map<String, Object> evaluateExpansion(@PathVariable long id, Authentication auth) {
        return expansion.evaluateAndDecide(id, auth == null ? "unknown" : auth.getName());
    }

    @PostMapping("/expansion/decide")
    public Map<String, Object> manualDecide(@PathVariable long id,
                                           @RequestBody Map<String, String> body,
                                           Authentication auth) {
        return expansion.manualDecide(id, body.get("decision"), body.get("reason"),
                auth == null ? "unknown" : auth.getName());
    }

    // ---------------- 离线审阅包 ----------------

    @GetMapping("/review-bundle")
    public Map<String, Object> reviewBundle(@PathVariable long id) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("generatedBy", "transit-pilot review bundle");
        bundle.put("experiment", jdbc.queryForMap("SELECT * FROM experiment WHERE id=?", id));
        bundle.put("routes", jdbc.queryForList("SELECT code,name FROM route WHERE experiment_id=?", id));
        bundle.put("preTransferEdges", jdbc.queryForList(
                "SELECT * FROM pre_transfer_edge WHERE experiment_id=? ORDER BY ratio DESC", id));
        bundle.put("clusters", jdbc.queryForList(
                "SELECT * FROM route_cluster WHERE experiment_id=? ORDER BY cluster_code", id));
        bundle.put("assignmentsTrip", jdbc.queryForList(
                "SELECT unit_key,stratum,arm,hash_value,inclusion_prob FROM assignment_unit "
                        + "WHERE experiment_id=? AND design_type='TRIP_RANDOMIZED' ORDER BY unit_key", id));
        bundle.put("assignmentsClusterWeek", jdbc.queryForList(
                "SELECT unit_key,stratum,cluster_code,week_index,arm,hash_value,inclusion_prob "
                        + "FROM assignment_unit WHERE experiment_id=? AND design_type='CLUSTER_WEEK' "
                        + "ORDER BY week_index, cluster_code", id));
        bundle.put("events", jdbc.queryForList(
                "SELECT event_type,severity,title,start_ts,end_ts,affected_routes,suspension "
                        + "FROM external_event WHERE experiment_id=? ORDER BY start_ts", id));
        bundle.put("demoJourneyLedgers", jdbc.queryForList("""
                SELECT journey_code,status,path_label,cluster_path_label,assigned_arm_trip,
                       assigned_arm_cluster,cross_line,diverted,path_probability,
                       path_detail::text AS path_detail
                FROM exposure_ledger
                WHERE experiment_id=? AND journey_code LIKE 'J-DEMO-%'
                ORDER BY journey_code
                """, id));
        bundle.put("supportStrata", jdbc.queryForList(
                "SELECT stratum_key,supported_trip,supported_cluster,gap_reason,n_total,"
                        + "w_t_trip,w_c_trip,w_t_cluster,w_c_cluster "
                        + "FROM support_stratum WHERE experiment_id=? ORDER BY stratum_key", id));
        bundle.put("analysisResults", jdbc.queryForList("""
                SELECT design_type,estimator_code,metric_code,scope,estimate,se,ci_low,ci_high,
                       p_value,n_journeys,n_units,detail
                FROM analysis_result WHERE experiment_id=?
                ORDER BY design_type,metric_code,estimator_code,scope
                """, id));
        bundle.put("guardrailEvaluations", jdbc.queryForList("""
                SELECT rule_code,observed,threshold,sample_n,breached,action,detail,evaluated_at
                FROM guardrail_evaluation WHERE experiment_id=?
                ORDER BY id DESC
                """, id));
        bundle.put("expansionDecisions", expansion.decisions(id));
        bundle.put("costReport", costService.report(id));
        return bundle;
    }
}
