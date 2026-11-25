package com.ccdev.transit.web;

import com.ccdev.transit.engine.AssignmentEngine;
import com.ccdev.transit.service.ExpansionService;
import com.ccdev.transit.service.RegistryService;
import com.ccdev.transit.web.dto.ExperimentRequest;
import org.springframework.jdbc.core.JdbcTemplate;
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
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final JdbcTemplate jdbc;
    private final RegistryService registry;
    private final ExpansionService expansion;

    public ExperimentController(JdbcTemplate jdbc, RegistryService registry, ExpansionService expansion) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.expansion = expansion;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.id,e.code,e.name,e.status,e.active_design,e.seed,e.assignment_prob,
                       e.saturation,e.pilot_weeks,e.start_date,e.end_date,e.registration_hash,
                       (SELECT decision FROM expansion_decision d WHERE d.experiment_id=e.id
                        ORDER BY id DESC LIMIT 1) AS latest_decision
                FROM experiment e ORDER BY e.id
                """);
        return rows;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ExperimentRequest request) {
        if (request.code() == null || request.code().isBlank() || request.name() == null) {
            throw new IllegalArgumentException("code 与 name 必填");
        }
        Long exists = jdbc.queryForObject(
                "SELECT count(*) FROM experiment WHERE code=?", Long.class, request.code());
        if (exists != null && exists > 0) {
            throw new IllegalStateException("实验编号已存在: " + request.code());
        }
        long id = registry.createDraft(request);
        return Map.of("id", id, "code", request.code(), "status", "DRAFT");
    }

    @GetMapping("/by-code/{code}")
    public Map<String, Object> byCode(@PathVariable String code) {
        return jdbc.queryForMap("SELECT * FROM experiment WHERE code=?", code);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> exp = jdbc.queryForMap("SELECT * FROM experiment WHERE id=?", id);
        exp.put("latestDecision", expansion.latestDecision(id));
        return exp;
    }

    @PostMapping("/{id}/register")
    public Map<String, Object> register(@PathVariable long id) {
        String hash = registry.register(id);
        return Map.of("id", id, "status", "REGISTERED", "registrationHash", hash);
    }

    @GetMapping("/{id}/overview")
    public Map<String, Object> overview(@PathVariable long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("journeys", count("SELECT count(*) FROM card_journey WHERE experiment_id=" + id));
        m.put("completeJourneys", count("SELECT count(*) FROM card_journey WHERE experiment_id=" + id + " AND status='COMPLETE'"));
        m.put("unknownJourneys", count("SELECT count(*) FROM card_journey WHERE experiment_id=" + id + " AND status<>'COMPLETE'"));
        m.put("demoJourneys", count("SELECT count(*) FROM card_journey WHERE experiment_id=" + id + " AND is_demo=TRUE"));
        m.put("tripInstances", count("SELECT count(*) FROM service_trip WHERE experiment_id=" + id));
        m.put("assignmentUnitsTrip", count("SELECT count(*) FROM assignment_unit WHERE experiment_id=" + id + " AND design_type='TRIP_RANDOMIZED'"));
        m.put("assignmentUnitsCluster", count("SELECT count(*) FROM assignment_unit WHERE experiment_id=" + id + " AND design_type='CLUSTER_WEEK'"));
        m.put("complaints", count("SELECT count(*) FROM complaint WHERE experiment_id=" + id));
        m.put("events", count("SELECT count(*) FROM external_event WHERE experiment_id=" + id));
        m.put("supportGaps", count("SELECT count(*) FROM support_stratum WHERE experiment_id=" + id
                + " AND NOT (supported_trip AND supported_cluster)"));
        m.put("blockedGuardrails", count("SELECT count(*) FROM guardrail_evaluation ge WHERE ge.experiment_id=" + id
                + " AND ge.breached=TRUE AND ge.id=(SELECT max(id) FROM guardrail_evaluation WHERE rule_code=ge.rule_code AND experiment_id=ge.experiment_id)"));
        m.put("latestDecision", expansion.latestDecision(id));
        return m;
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    @GetMapping("/{id}/network")
    public Map<String, Object> network(@PathVariable long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("routes", jdbc.queryForList("SELECT code,name FROM route WHERE experiment_id=? ORDER BY code", id));
        m.put("stops", jdbc.queryForList("SELECT code,name,kind FROM stop_ WHERE experiment_id=? ORDER BY id", id));
        m.put("preTransferEdges", jdbc.queryForList(
                "SELECT from_route,to_route,transfer_journeys,total_journeys,ratio,frozen "
                        + "FROM pre_transfer_edge WHERE experiment_id=? ORDER BY ratio DESC", id));
        m.put("clusters", jdbc.queryForList(
                "SELECT cluster_code,route_codes FROM route_cluster WHERE experiment_id=? ORDER BY cluster_code", id));

        double maxRatio = jdbc.queryForList(
                        "SELECT max(ratio) mx FROM pre_transfer_edge WHERE experiment_id=?", id).stream()
                .findFirst().map(r -> r.get("mx") == null ? 0.0 : ((Number) r.get("mx")).doubleValue())
                .orElse(0.0);
        int clusterCount = m.get("clusters") instanceof List<?> l ? l.size() : 0;
        int weeks = jdbc.queryForObject("SELECT pilot_weeks FROM experiment WHERE id=?", Integer.class, id);
        int routeCount = ((List<?>) m.get("routes")).size();
        Long tripUnits = jdbc.queryForObject(
                "SELECT count(*) FROM assignment_unit WHERE experiment_id=? AND design_type='TRIP_RANDOMIZED'",
                Long.class, id);
        int tripUnitCount = tripUnits == null ? routeCount * 20 * 8 : tripUnits.intValue();

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("edgeRatioMax", maxRatio);
        options.put("transferShareR1R2", 0.40);
        options.put("tripRandomization", Map.of(
                "effectiveUnits", tripUnitCount,
                "strata", routeCount * 20 * 3,
                "unit", "线路 × 运营日 × 时段 × 具体班次",
                "saturation", "每个线路×日期×时段层 4 班中平衡取 2 个处理，全网络严格 50%",
                "advantage", "随机化单元多、时效分辨率高，CI 较窄",
                "risk", "共享枢纽污染/分流暴露校正负担大，对照乘客可能搭乘处理线路"));
        options.put("clusterWeekRandomization", Map.of(
                "effectiveUnits", clusterCount * weeks,
                "clusters", clusterCount,
                "clusterWeeks", clusterCount * weeks,
                "saturation", "每周两簇中一簇处理，严格 50%",
                "advantage", "簇内统一处理，内部换乘不构成异臂污染",
                "risk", clusterCount + " 个簇偏少，CI 更宽，必须核验簇间对照与 RI"));
        String recommendation;
        if (maxRatio >= 0.25 && clusterCount < 4) {
            recommendation = "实验前 R1↔R2 换乘边比例高达 " + Math.round(maxRatio * 100)
                    + "%（强连接，班次随机污染严重），但可用线路簇仅 " + clusterCount
                    + " 个（簇—周有效单元 " + (clusterCount * weeks)
                    + " 个），簇随机不确定性大。两方案都应报告：班次方案作主估计并做完整路径暴露校正，"
                    + "簇周方案作降污染敏感性分析并用随机化推断。选择依据是实验前边比例/簇数/有效单元，而非试点结果。";
        } else {
            recommendation = "按实验前网络指标选择随机化粒度。";
        }
        options.put("recommendation", recommendation);
        m.put("designOptions", options);
        return m;
    }

    @GetMapping("/{id}/assignments")
    public Map<String, Object> assignments(@PathVariable long id,
                                           @RequestParam(defaultValue = "TRIP_RANDOMIZED") String design) {
        List<Map<String, Object>> units = jdbc.queryForList("""
                SELECT unit_key,stratum,label,route_codes,cluster_code,week_index,
                       hash_value,arm,inclusion_prob
                FROM assignment_unit WHERE experiment_id=? AND design_type=?
                ORDER BY stratum, arm DESC, unit_key
                """, id, design);
        long t = units.stream().filter(u -> AssignmentEngine.TREATMENT.equals(u.get("arm"))).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("design", design);
        m.put("units", units);
        m.put("total", units.size());
        m.put("treatment", t);
        m.put("control", units.size() - t);
        m.put("treatmentShare", units.isEmpty() ? 0 : Math.round(t * 10000.0 / units.size()) / 10000.0);
        return m;
    }

    @GetMapping("/{id}/events")
    public List<Map<String, Object>> events(@PathVariable long id) {
        return jdbc.queryForList("""
                SELECT event_type,severity,title,start_ts,end_ts,affected_routes,suspension,source
                FROM external_event WHERE experiment_id=? ORDER BY start_ts
                """, id);
    }

    @GetMapping("/{id}/complaints")
    public Map<String, Object> complaints(@PathVariable long id,
                                          @RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT complaint_code,route_code,category,severity,complaint_time,service_event_time,
                       channel,journey_id
                FROM complaint WHERE experiment_id=?
                ORDER BY service_event_time DESC LIMIT ?
                """, id, limit);
        Long lagged = jdbc.queryForObject("""
                SELECT count(*) FROM complaint WHERE experiment_id=?
                AND complaint_time::date <> service_event_time::date
                """, Long.class, id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("laggedReportCount", lagged);
        m.put("timeBasis", "护栏与时间线按 service_event_time（服务发生时间）关联，投诉提交时间仅作留痕");
        return m;
    }
}
