package com.ccdev.transit.service;

import com.ccdev.transit.engine.AssignmentEngine;
import com.ccdev.transit.engine.TripTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 构造题目要求的虚构样例（全部由固定种子确定性生成，可重复）：
 * R1/R2 共享枢纽且实验前 40% 两线换乘，R3 基本不换乘；
 * R1 一个处理班次在 2026-06-02 实际短线折返，R2 对照班次承接分流；
 * 另有暴雨停运（06-15）、司机全程不执行新规则、施工/临时改线事件。
 */
@Service
@Order(20)
public class SeedService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);
    public static final String SAMPLE_CODE = "PILOT-R123-202606";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final RegistryService registry;
    private final PipelineService pipeline;
    private final Random rng = new Random(20260601L);

    private LocalDate start = LocalDate.of(2026, 6, 1);
    private LocalDate stormDate = LocalDate.of(2026, 6, 15);
    private LocalDate rainDate = LocalDate.of(2026, 6, 8);
    private LocalDate divertDate = LocalDate.of(2026, 6, 2);

    private final Map<String, String> tripArm = new HashMap<>();
    private final Map<String, String> cwArm = new HashMap<>();
    private final Map<String, String> routeCluster = new HashMap<>();
    private final Map<String, TripCtx> tripByKey = new HashMap<>();
    private final Map<String, TripCtx> tripByUnit = new HashMap<>();
    private final Map<String, List<TripCtx>> bandIndex = new HashMap<>();
    private final Map<Long, RunState> runState = new HashMap<>();

    private String shortTurnUnit;
    private String divertR2Unit;
    private final String nonExecTemplate = "PM3"; // R1 该模板的处理实例上司机多次不执行新规则

    public SeedService(JdbcTemplate jdbc, PasswordEncoder encoder,
                       RegistryService registry, PipelineService pipeline) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.registry = registry;
        this.pipeline = pipeline;
    }

    @Override
    public void run(String... args) {
        seedUsers();
        Long existing = jdbc.queryForObject(
                "SELECT count(*) FROM experiment WHERE code = ?", Long.class, SAMPLE_CODE);
        if (existing != null && existing > 0) {
            log.info("样例实验已存在，跳过播种；如需重算请调用 /pipeline/run");
            return;
        }
        seedSample();
    }

    private void seedUsers() {
        createUser("viewer", "viewer123", "审阅访客", "VIEWER");
        createUser("analyst", "analyst123", "实验分析师", "ANALYST");
        createUser("approver", "approver123", "扩围审批人", "APPROVER");
    }

    private void createUser(String u, String p, String name, String role) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username=?", Integer.class, u);
        if (n != null && n > 0) {
            return;
        }
        jdbc.update("INSERT INTO app_user(username,password_hash,display_name,role) VALUES (?,?,?,?)",
                u, encoder.encode(p), name, role);
    }

    // ============================ 主播种流程 ============================

    private void seedSample() {
        log.info("开始构造虚构样例 ...");
        long expId = registry.createDraft(new com.ccdev.transit.web.dto.ExperimentRequest(
                SAMPLE_CODE,
                "市公交集团 R1/R2/R3 缩短发车间隔与短线折返灰度试点（虚构样例）",
                "TRIP_RANDOMIZED",
                "TSP-PILOT-2026-06-01",
                0.5, 4, start.toString(),
                "试点网络内三条线路在 2026-06-01 至 2026-06-28 期间的刷卡乘客完整旅程（首乘+换乘）",
                "高峰发车间隔压缩约120秒；断面高负荷时在枢纽/折返站短线折返",
                0.15, 25,
                List.of(
                        new com.ccdev.transit.web.dto.ExperimentRequest.RouteDto("R1", "一路"),
                        new com.ccdev.transit.web.dto.ExperimentRequest.RouteDto("R2", "二路"),
                        new com.ccdev.transit.web.dto.ExperimentRequest.RouteDto("R3", "三路")),
                buildEdges()));
        registry.register(expId);

        seedStops(expId);
        seedGuardrails(expId);
        loadAssignments(expId);
        chooseSpecialUnits();
        seedEvents(expId);
        seedTripsAndRuns(expId);
        seedDemoJourneys(expId);
        seedDivertedBulk(expId);
        seedStormJourneys(expId);
        seedBulkJourneys(expId);

        pipeline.rebuildAll(expId);
        log.info("虚构样例构造完成 experimentId={}", expId);
    }

    private List<com.ccdev.transit.web.dto.ExperimentRequest.EdgeDto> buildEdges() {
        List<com.ccdev.transit.web.dto.ExperimentRequest.EdgeDto> edges = new ArrayList<>();
        // R1/R2 共享枢纽，基线 40% 两线换乘（比例由 transfer/total 决定）
        edges.add(edge("R1", "R2", 400, 1000)); // 0.40
        edges.add(edge("R2", "R1", 380, 1000)); // 0.38
        edges.add(edge("R1", "R3", 12, 1000));
        edges.add(edge("R3", "R1", 18, 900));
        edges.add(edge("R2", "R3", 16, 950));
        edges.add(edge("R3", "R2", 11, 900));
        return edges;
    }

    private com.ccdev.transit.web.dto.ExperimentRequest.EdgeDto edge(String a, String b, int tj, int tt) {
        return new com.ccdev.transit.web.dto.ExperimentRequest.EdgeDto(a, b, tj, tt);
    }

    private void seedStops(long expId) {
        String[][] stops = {
                {"S-XIYUAN", "西园", "NORMAL"}, {"S-WHG", "文化宫", "NORMAL"},
                {"HUB-CENTRAL", "中心枢纽", "HUB"}, {"S-EAST", "东站", "NORMAL"},
                {"S-BEIGANG", "北港", "NORMAL"}, {"S-TYG", "体育馆", "NORMAL"},
                {"S-NANYUAN", "南园", "NORMAL"}, {"S-GX", "高新区", "NORMAL"},
                {"S-UNI", "大学城", "NORMAL"}, {"S-HSR", "高铁站", "NORMAL"}
        };
        for (String[] s : stops) {
            jdbc.update("INSERT INTO stop_(experiment_id,code,name,kind) VALUES (?,?,?,?)",
                    expId, s[0], s[1], s[2]);
        }
    }

    private void seedGuardrails(long expId) {
        rule(expId, "COMPLAINT_RATE_MAX", "投诉率护栏：每万乘客投诉数", "complaint_rate_per_10k", "GT", 45, 10);
        rule(expId, "OVERLOAD_SHARE_MAX", "满载护栏：LF≥1.0 班次占比", "overload_share", "GT", 0.05, 100);
        rule(expId, "STRANDING_RATE_MAX", "安全护栏：未完成/滞留旅程占比", "stranding_rate", "GT", 0.03, 100);
    }

    private void rule(long expId, String code, String name, String src, String cmp, double th, int minN) {
        jdbc.update("""
                INSERT INTO guardrail_rule(experiment_id,code,name,metric_source,comparator,threshold,min_sample)
                VALUES (?,?,?,?,?,?,?)
                """, expId, code, name, src, cmp, th, minN);
    }

    private void loadAssignments(long expId) {
        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT design_type,unit_key,arm,cluster_code FROM assignment_unit WHERE experiment_id=?", expId)) {
            String design = String.valueOf(m.get("design_type"));
            String key = String.valueOf(m.get("unit_key"));
            String arm = String.valueOf(m.get("arm"));
            if ("TRIP_RANDOMIZED".equals(design)) {
                tripArm.put(key, arm);
            } else {
                cwArm.put(key, arm);
            }
            if (m.get("cluster_code") != null) {
                // 线路->簇映射从 route_cluster 取
            }
        }
        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT cluster_code, route_codes FROM route_cluster WHERE experiment_id=?", expId)) {
            String cc = String.valueOf(m.get("cluster_code"));
            for (String r : String.valueOf(m.get("route_codes")).split(",")) {
                routeCluster.put(r.trim(), cc);
            }
        }
    }

    private void chooseSpecialUnits() {
        // 著名的 R1 处理班次（2026-06-02 早高峰，实际短线折返）：日期级单元中选 T
        shortTurnUnit = tripArm.keySet().stream()
                .filter(k -> k.startsWith("TRIP:R1:" + divertDate + ":AM")
                        && AssignmentEngineHolder.T.equals(tripArm.get(k)))
                .min((a, b) -> {
                    // 优先 AM2，其次 AM1/AM3
                    int ra = a.endsWith("AM2") ? 0 : 1;
                    int rb = b.endsWith("AM2") ? 0 : 1;
                    return Integer.compare(ra, rb);
                })
                .orElseThrow();
        // 承接分流的 R2 对照 AM 班次（优先 AM1，与折返时刻衔接）
        divertR2Unit = tripArm.keySet().stream()
                .filter(k -> k.startsWith("TRIP:R2:" + divertDate + ":AM")
                        && AssignmentEngineHolder.C.equals(tripArm.get(k)))
                .min((a, b) -> {
                    int ra = a.endsWith("AM1") ? 0 : 1;
                    int rb = b.endsWith("AM1") ? 0 : 1;
                    return Integer.compare(ra, rb);
                })
                .orElseThrow();
        log.info("特殊班次 shortTurn={} divertR2={} nonExecTemplate=R1/{}",
                shortTurnUnit, divertR2Unit, nonExecTemplate);
    }

    private boolean isNonExec(TripCtx ctx) {
        return "R1".equals(ctx.route) && nonExecTemplate.equals(ctx.tplCode)
                && AssignmentEngineHolder.T.equals(ctx.armTrip);
    }

    private void seedEvents(long expId) {
        event(expId, "WEATHER_RAINSTORM", "RED", "暴雨红色预警：R1/R2 早高峰部分停运",
                stormDate.atTime(6, 30), stormDate.atTime(10, 30), "R1,R2", true, "市气象台");
        event(expId, "WEATHER_RAIN", "YELLOW", "全天中到大雨，运行降速",
                rainDate.atTime(0, 0), rainDate.atTime(23, 59), "*", false, "市气象台");
        event(expId, "CONSTRUCTION", "ORANGE", "道路施工：科技大道占用 R3 车道",
                LocalDate.of(2026, 6, 10).atTime(22, 0),
                LocalDate.of(2026, 6, 12).atTime(6, 0), "R3", false, "市政施工公告");
        event(expId, "ROUTE_CHANGE", "YELLOW", "临时改线：R2 周五晚高峰绕过体育馆站",
                LocalDate.of(2026, 6, 19).atTime(16, 0),
                LocalDate.of(2026, 6, 19).atTime(20, 0), "R2", false, "公交调度中心");
    }

    private void event(long expId, String type, String sev, String title,
                       LocalDateTime s, LocalDateTime e, String routes, boolean suspension, String src) {
        jdbc.update("""
                INSERT INTO external_event(experiment_id,event_type,severity,title,start_ts,end_ts,
                    affected_routes,suspension,source)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, expId, type, sev, title, Timestamp.valueOf(s), Timestamp.valueOf(e),
                routes, suspension, src);
    }

    // ============================ 计划班次 + 实际运行 ============================

    private void seedTripsAndRuns(long expId) {
        for (int w = 1; w <= 4; w++) {
            for (int dow = 0; dow < 5; dow++) {
                LocalDate date = start.plusDays((long) (w - 1) * 7 + dow);
                for (String route : List.of("R1", "R2", "R3")) {
                    for (TripTemplates.TemplateSpec tpl : TripTemplates.ALL) {
                        insertTripAndRun(expId, w, date, route, tpl);
                    }
                }
            }
        }
    }

    private void insertTripAndRun(long expId, int week, LocalDate date, String route,
                                  TripTemplates.TemplateSpec tpl) {
        String unitKey = "TRIP:" + route + ":" + date + ":" + tpl.code();
        String armTrip = tripArm.get(unitKey);
        String cc = routeCluster.get(route);
        String cwKey = "CW:" + cc + ":W" + week;
        String armCluster = cwArm.get(cwKey);
        LocalDateTime ts = date.atTime(tpl.hour(), tpl.minute());
        String tripCode = route + "-" + date + "-" + tpl.code();

        Long tripId = jdbc.queryForObject("""
                INSERT INTO service_trip(experiment_id,trip_code,template_key,cluster_week_key,
                    route_code,service_date,week_index,period_band,seq_in_band,planned_departure,capacity)
                VALUES (?,?,?,?,?,?,?,?,?,?,80) RETURNING id
                """, Long.class, expId, tripCode, unitKey, cwKey, route,
                java.sql.Date.valueOf(date), week, tpl.band(), tpl.seq(), Timestamp.valueOf(ts));

        TripCtx ctx = new TripCtx(tripId, tripCode, route, date, week, tpl.band(),
                tpl.code(), unitKey, armTrip, cwKey, cc, armCluster, ts);
        tripByKey.put(tripCode, ctx);
        tripByUnit.put(unitKey, ctx);
        bandIndex.computeIfAbsent(route + "|" + date + "|" + tpl.band(), k -> new ArrayList<>()).add(ctx);

        RunState rs = buildRunState(ctx);
        runState.put(tripId, rs);

        Long runId = jdbc.queryForObject("""
                INSERT INTO actual_service_run(trip_id,gps_run_code,actual_departure,actual_arrival,
                    headway_seconds,rule_executed,deviation,short_turn_at_stop,diverted_to_route,
                    delay_seconds,observed_lf,source)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, 'GPS_AVL') RETURNING id
                """, Long.class, tripId, "GPS-" + tripCode,
                rs.suspended ? null : Timestamp.valueOf(ts.plusSeconds(Math.max(0, rs.delay - 60))),
                rs.suspended ? null : Timestamp.valueOf(ts.plusMinutes(route.equals("R3") ? 38 : 30)),
                rs.suspended ? null : rs.headway,
                rs.executed, rs.deviation, rs.shortTurnStop, rs.divertedRoute,
                rs.delay, rs.suspended ? null : rs.lf);
        rs.runId = runId;

        if (!rs.suspended) {
            int pax = (int) Math.round(rs.lf * 80);
            jdbc.update("""
                    INSERT INTO load_count(actual_run_id,trip_id,observed_at,passengers,capacity,load_factor)
                    VALUES (?,?,?,?,80,?)
                    """, runId, tripId, Timestamp.valueOf(ts.plusMinutes(25)), pax, rs.lf);
        }
    }

    private RunState buildRunState(TripCtx ctx) {
        RunState rs = new RunState();
        boolean assignedT = AssignmentEngineHolder.T.equals(ctx.armTrip);
        rs.executed = assignedT && !isNonExec(ctx);

        double baseLf = switch (ctx.band) {
            case "AM_PEAK" -> 0.84;
            case "PM_PEAK" -> 0.80;
            default -> 0.52;
        };
        double lf = baseLf + rng.nextGaussian() * 0.12;
        if (rs.executed) {
            lf -= 0.08; // 缩短间隔降低单车满载
        }
        rs.lf = clamp(lf, 0.18, 1.28);
        rs.headway = rs.executed
                ? (int) Math.round(240 + rng.nextGaussian() * 18)
                : (int) Math.round(360 + rng.nextGaussian() * 28);
        rs.delay = (int) Math.max(0, Math.round(rng.nextGaussian() * 40 + 30));
        rs.deviation = "NONE";

        // 著名短线折返：仅 2026-06-02 该模板实例
        if (ctx.unitKey.equals(shortTurnUnit) && ctx.date.equals(divertDate)) {
            rs.deviation = "SHORT_TURN";
            rs.shortTurnStop = "HUB-CENTRAL";
            rs.divertedRoute = "R2";
            rs.delay = 260;
        }
        // R2 对照班次承接分流客流，满载显著升高
        if (ctx.unitKey.equals(divertR2Unit) && ctx.date.equals(divertDate)) {
            rs.lf = 1.17;
        }
        // 暴雨红色预警：R1/R2 早高峰停运
        if (ctx.date.equals(stormDate) && "AM_PEAK".equals(ctx.band)
                && (ctx.route.equals("R1") || ctx.route.equals("R2"))) {
            rs.suspended = true;
            rs.deviation = "SUSPENDED";
            rs.executed = false;
            rs.lf = 0;
        }
        // 施工：06-11 R3 大幅延误
        if (ctx.date.equals(LocalDate.of(2026, 6, 11)) && ctx.route.equals("R3")) {
            rs.deviation = "DELAY";
            rs.delay = 320;
        }
        // 临时改线：06-19 R2 晚高峰延误
        if (ctx.date.equals(LocalDate.of(2026, 6, 19)) && ctx.route.equals("R2")
                && "PM_PEAK".equals(ctx.band)) {
            rs.deviation = "DELAY";
            rs.delay = 180;
        }
        return rs;
    }

    // ============================ 命名核验旅程 ============================

    private void seedDemoJourneys(long expId) {
        // 1) 著名分流：R1 处理班次短线折返 -> R2 对照班次
        TripCtx d1 = tripByUnit.get(shortTurnUnit);
        TripCtx d2 = tripByUnit.get(divertR2Unit);
        persistComplete(expId, "J-DEMO-DIVERT-01", d1.plannedTs, "R1R2", "OD_MID",
                "S-XIYUAN", "HUB-CENTRAL", "S-NANYUAN", 1, true,
                "R1分配处理班次实际短线折返，乘客在中心枢纽换乘R2对照班次（完整路径须标 CROSS_MIXED）",
                List.of(d1, d2));

        // 2) 同一 R2 对照班次上的普通 R2 乘客 -> 纯净对照
        persistComplete(expId, "J-DEMO-PURER2-01", d2.plannedTs.plusMinutes(3), "R2ONLY", "OD_SHORT",
                "S-BEIGANG", null, "S-NANYUAN", 1, true,
                "与分流乘客同乘R2对照班次，但本身只坐R2：必须保留在 FULL_CONTROL",
                List.of(d2));

        // 3) 对照乘客在枢纽搭乘处理 R2 班次
        TripCtx c1 = requireTripArm("R1", LocalDate.of(2026, 6, 3), "AM", AssignmentEngineHolder.C);
        TripCtx t2 = requireTripArm("R2", LocalDate.of(2026, 6, 3), "AM", AssignmentEngineHolder.T);
        persistComplete(expId, "J-DEMO-CROSS-01", c1.plannedTs, "R1R2", "OD_MID",
                "S-WHG", "HUB-CENTRAL", "S-TYG", 1, true,
                "起乘R1对照班次，枢纽换乘R2处理班次：对照乘客搭乘处理线路，须标 CROSS_MIXED",
                List.of(c1, t2));

        // 4) 全程处理
        TripCtx ft1 = requireTripArm("R1", LocalDate.of(2026, 6, 4), "AM", AssignmentEngineHolder.T);
        TripCtx ft2 = requireTripArm("R2", LocalDate.of(2026, 6, 4), "AM", AssignmentEngineHolder.T);
        persistComplete(expId, "J-DEMO-FULLT-01", ft1.plannedTs, "R1R2", "OD_MID",
                "S-XIYUAN", "HUB-CENTRAL", "S-NANYUAN", 1, true,
                "R1/R2 两段均为处理且实际执行：FULL_TREATMENT", List.of(ft1, ft2));

        // 5) 全程对照
        TripCtx fc1 = requireTripArm("R1", LocalDate.of(2026, 6, 4), "AM", AssignmentEngineHolder.C);
        TripCtx fc2 = requireTripArm("R2", LocalDate.of(2026, 6, 4), "AM", AssignmentEngineHolder.C);
        persistComplete(expId, "J-DEMO-FULLC-01", fc1.plannedTs, "R1R2", "OD_MID",
                "S-WHG", "HUB-CENTRAL", "S-BEIGANG", 1, true,
                "R1/R2 两段均为对照：FULL_CONTROL", List.of(fc1, fc2));

        // 6) 分配处理但司机未执行 -> ITT 仍在处理组，实际暴露为旧规则
        TripCtx ne = tripByUnit.values().stream().filter(this::isNonExec).findFirst().orElseThrow();
        persistComplete(expId, "J-DEMO-NONEXEC-01", ne.plannedTs, "R1ONLY", "OD_SHORT",
                "S-XIYUAN", null, "S-WHG", 1, true,
                "所乘班次随机分配=T，但司机未执行新规则：实际暴露=旧规则，分配臂仍=T",
                List.of(ne));

        // 7/8) 未知旅程
        persistUnknown(expId, "J-DEMO-INCOMPLETE-01",
                LocalDate.of(2026, 6, 4).atTime(8, 5), "R1ONLY", "OD_SHORT",
                "INCOMPLETE_TAP", "S-XIYUAN", "HUB-CENTRAL",
                "下车/换乘刷卡缺失：保留 UNKNOWN，不得丢弃或强行归组",
                List.of(requireAnyTrip("R1", LocalDate.of(2026, 6, 4), "AM")));
        persistUnknown(expId, "J-DEMO-MIDCHANGE-01",
                LocalDate.of(2026, 6, 4).atTime(18, 12), "R2ONLY", "OD_MID",
                "MID_ROUTE_CHANGE", "S-BEIGANG", "S-NANYUAN",
                "中途临时改线且路径无法还原：保留 UNKNOWN",
                List.of(requireAnyTrip("R2", LocalDate.of(2026, 6, 4), "PM")));

        // 9) 暴雨停运导致中途改线未知
        TripCtx stormTrip = bandIndex.getOrDefault("R1|" + stormDate + "|AM_PEAK", List.of()).stream()
                .findFirst().orElseThrow();
        persistUnknown(expId, "J-DEMO-STORM-01",
                stormDate.atTime(7, 12), "R1R2", "OD_LONG",
                "MID_ROUTE_CHANGE", "S-XIYUAN", "HUB-CENTRAL",
                "暴雨红色预警 R1 停运，乘客中途改线，端到端结果未知；计入安全护栏",
                List.of(stormTrip));
    }

    // ============================ 批量分流/暴雨/普通旅程 ============================

    private void seedDivertedBulk(long expId) {
        TripCtx r1 = tripByUnit.get(shortTurnUnit);
        TripCtx r2 = tripByUnit.get(divertR2Unit);
        for (int i = 1; i <= 22; i++) {
            int pax = 1 + (rng.nextDouble() < 0.25 ? 1 : 0);
            JSpec s = baseSpec("J-DIVERT-" + String.format("%03d", i),
                    r1.plannedTs.plusMinutes(rng.nextInt(6)), "R1R2", "OD_MID", pax);
            s.origin = "S-XIYUAN"; s.hub = "HUB-CENTRAL"; s.dest = "S-NANYUAN";
            s.note = "短线折返分流批量旅程";
            persistComplete(expId, s, List.of(r1, r2));
        }
    }

    private void seedStormJourneys(long expId) {
        List<TripCtx> r1Am = bandIndex.get("R1|" + stormDate + "|AM_PEAK");
        List<TripCtx> r2Am = bandIndex.get("R2|" + stormDate + "|AM_PEAK");
        int seq = 1;
        // 18 个未完成刷卡
        for (int i = 0; i < 18; i++) {
            TripCtx t = pick(r1Am);
            JSpec s = baseSpec("J-STORM-UNK-" + String.format("%03d", seq++),
                    stormDate.atTime(6, 45).plusMinutes(rng.nextInt(90)), "R1ONLY",
                    rng.nextDouble() < .5 ? "OD_SHORT" : "OD_MID", 1 + rng.nextInt(2));
            s.weather = "RAINSTORM"; s.status = "INCOMPLETE_TAP";
            s.note = "暴雨停运，刷卡不完整";
            persistUnknown(expId, s, List.of(t));
        }
        // 12 个中途改线
        for (int i = 0; i < 12; i++) {
            TripCtx t = pick(r1Am);
            JSpec s = baseSpec("J-STORM-CHG-" + String.format("%03d", seq++),
                    stormDate.atTime(7, 0).plusMinutes(rng.nextInt(120)), "R1R2", "OD_LONG", 1 + rng.nextInt(2));
            s.weather = "RAINSTORM"; s.status = "MID_ROUTE_CHANGE";
            s.note = "暴雨停运，中途改线";
            persistUnknown(expId, s, List.of(t));
        }
        // 16 个完成但无共同支持（accessibility=false，极端等待）
        for (int i = 0; i < 16; i++) {
            TripCtx t1 = pick(r1Am);
            TripCtx t2 = pick(r2Am);
            JSpec s = baseSpec("J-STORM-CMP-" + String.format("%03d", i + 1),
                    stormDate.atTime(7, 0).plusMinutes(rng.nextInt(100)), "R1R2", "OD_LONG", 1 + rng.nextInt(2));
            s.weather = "RAINSTORM"; s.accessibility = false;
            s.note = "暴雨后绕行完成，服务可达性受损，不进入可外推共同支持域";
            persistComplete(expId, s, List.of(t1, t2));
        }
    }

    private void seedBulkJourneys(long expId) {
        List<LocalDate> dates = new ArrayList<>();
        for (int w = 1; w <= 4; w++) {
            for (int dow = 0; dow < 5; dow++) {
                LocalDate d = start.plusDays((long) (w - 1) * 7 + dow);
                if (!d.equals(stormDate)) {
                    dates.add(d);
                }
            }
        }

        for (int i = 1; i <= 2400; i++) {
            LocalDate date = dates.get(rng.nextInt(dates.size()));
            String band = drawBand();
            String pattern = drawPattern();
            String od = drawOd();
            int pax = 1 + (rng.nextDouble() < .18 ? 1 : 0) + (rng.nextDouble() < .05 ? 1 : 0);
            LocalDateTime ts = date.atTime(hourFor(band), rng.nextInt(59));

            // 约 1.8% 随机未完成/改线 -> UNKNOWN 保留
            if (rng.nextDouble() < 0.018) {
                String route = pattern.startsWith("R3") ? "R3" : (pattern.equals("R2ONLY") ? "R2" : "R1");
                TripCtx t = pick(bandIndex.get(route + "|" + date + "|" + band));
                if (t == null) continue;
                JSpec s = baseSpec(String.format("J-BULK-U%05d", i), ts, pattern, od, pax);
                s.status = rng.nextBoolean() ? "INCOMPLETE_TAP" : "MID_ROUTE_CHANGE";
                persistUnknown(expId, s, List.of(t));
                continue;
            }

            List<TripCtx> trips = selectPath(date, band, pattern);
            if (trips.isEmpty()) {
                continue;
            }
            JSpec s = baseSpec(String.format("J-BULK-%05d", i), ts, pattern, od, pax);
            persistComplete(expId, s, trips);
        }
    }

    private List<TripCtx> selectPath(LocalDate date, String band, String pattern) {
        List<TripCtx> path = new ArrayList<>();
        switch (pattern) {
            case "R1ONLY" -> path.add(pick(bandIndex.get("R1|" + date + "|" + band)));
            case "R2ONLY" -> path.add(pick(bandIndex.get("R2|" + date + "|" + band)));
            case "R3ONLY" -> path.add(pick(bandIndex.get("R3|" + date + "|" + band)));
            case "R1R2" -> {
                boolean r1First = rng.nextBoolean();
                path.add(pick(bandIndex.get((r1First ? "R1" : "R2") + "|" + date + "|" + band)));
                path.add(pick(bandIndex.get((r1First ? "R2" : "R1") + "|" + date + "|" + band)));
            }
            default -> { // R1R3 / R2R3 少量
                String a = rng.nextBoolean() ? "R1" : "R2";
                path.add(pick(bandIndex.get(a + "|" + date + "|" + band)));
                path.add(pick(bandIndex.get("R3|" + date + "|" + band)));
            }
        }
        path.removeIf(java.util.Objects::isNull);
        return path;
    }

    // ============================ 结果生成（同一 DGP，两套分配实现） ============================

    private void persistComplete(long expId, String code, LocalDateTime ts, String pattern,
                                 String od, String origin, String hub, String dest,
                                 int pax, boolean demo, String note, List<TripCtx> trips) {
        JSpec s = baseSpec(code, ts, pattern, od, pax);
        s.origin = origin; s.hub = hub; s.dest = dest; s.demo = demo; s.note = note;
        persistComplete(expId, s, trips);
    }

    private void persistComplete(long expId, JSpec s, List<TripCtx> trips) {
        if (trips == null || trips.isEmpty() || trips.stream().anyMatch(java.util.Objects::isNull)) {
            return;
        }
        computeOutcomes(s, trips);
        insertJourney(expId, s, trips);
        maybeComplain(expId, s, trips);
    }

    private void persistUnknown(long expId, String code, LocalDateTime ts, String pattern, String od,
                               String status, String origin, String hub, String note, List<TripCtx> trips) {
        JSpec s = baseSpec(code, ts, pattern, od, 1);
        s.status = status;
        s.origin = origin; s.hub = hub; s.note = note; s.demo = true;
        persistUnknown(expId, s, trips);
    }

    private void persistUnknown(long expId, JSpec s, List<TripCtx> trips) {
        if (trips == null || trips.isEmpty() || trips.stream().anyMatch(java.util.Objects::isNull)) {
            return;
        }
        insertJourney(expId, s, trips);
        // 未知/停运旅程投诉概率更高
        double p = s.weather.equals("RAINSTORM") ? 0.34 : 0.16;
        if (rng.nextDouble() < p) {
            insertComplaint(expId, s.id, s.code, trips.get(0).route,
                    rng.nextBoolean() ? "SERVICE_DEVIATION" : "TAP_ISSUE",
                    "SERIOUS", s.ts);
            jdbc.update("UPDATE card_journey SET complaint_count=1 WHERE id=?", s.id);
        }
    }

    private void computeOutcomes(JSpec s, List<TripCtx> trips) {
        int l = trips.size();
        double[] base = new double[l];
        double[] noise = new double[l];
        double[] reductionDraw = new double[l];
        double[] ride = new double[l];
        double[] lfNoise = new double[l];

        for (int i = 0; i < l; i++) {
            TripCtx t = trips.get(i);
            if (i == 0) {
                base[i] = switch (t.band) {
                    case "AM_PEAK" -> 265;
                    case "PM_PEAK" -> 320;
                    default -> 430;
                };
                noise[i] = rng.nextGaussian() * 42;
            } else {
                base[i] = 205;
                noise[i] = rng.nextGaussian() * 38;
            }
            reductionDraw[i] = 72 + rng.nextDouble() * 18;
            ride[i] = switch (s.odBand) {
                case "OD_SHORT" -> 9 + rng.nextDouble() * 4;
                case "OD_LONG" -> 25 + rng.nextDouble() * 7;
                default -> 15 + rng.nextDouble() * 5;
            };
            lfNoise[i] = rng.nextGaussian() * 0.05;
        }

        double firstWait = 0, xferWait = 0, totalWait = 0;
        double crowd = 0, severe = 0, maxLf = 0;
        double crowdC = 0, severeC = 0;
        double totalWaitCluster = 0;

        for (int i = 0; i < l; i++) {
            TripCtx t = trips.get(i);
            RunState rs = runState.get(t.id);

            // ---- 班次随机世界（实际运行状态） ----
            double w = Math.max(45, base[i] + noise[i]);
            boolean tripEffective = AssignmentEngineHolder.T.equals(t.armTrip)
                    && rs != null && rs.executed && !rs.suspended;
            if (tripEffective) {
                w -= reductionDraw[i];
            }
            if (rs != null) {
                w += rs.delay * 0.35;
            }
            // 短线折返后接续：分流乘客额外等待下一班 R2
            if (i > 0) {
                RunState prev = runState.get(trips.get(i - 1).id);
                if (prev != null && "SHORT_TURN".equals(prev.deviation)) {
                    w += 250 + rng.nextDouble() * 60;
                }
            }
            if (!s.accessibility) {
                w += 620 + rng.nextDouble() * 320;
            }
            w = Math.max(40, w);
            if (i == 0) firstWait = w; else xferWait += w;
            totalWait += w;

            double lf = rs != null && !rs.suspended ? clamp(rs.lf + lfNoise[i], 0.15, 1.35) : 0.9;
            double rideMin = ride[i];
            crowd += lf * rideMin;
            if (lf >= 1.0) severe += rideMin;
            maxLf = Math.max(maxLf, lf);

            // ---- 簇—周世界：同簇 R1/R2 同臂，分流由簇内加班消化 ----
            double wc = Math.max(45, base[i] + noise[i]);
            boolean clusterEffective = AssignmentEngineHolder.T.equals(t.armCluster);
            if (clusterEffective) {
                wc -= reductionDraw[i];
            }
            if (rs != null) {
                wc += rs.delay * 0.35;
            }
            if (i > 0) {
                RunState prev = runState.get(trips.get(i - 1).id);
                if (prev != null && "SHORT_TURN".equals(prev.deviation)) {
                    // 簇内 R2 同周也处于新规则，运能补充，代价显著更小
                    wc += 80 + rng.nextDouble() * 40;
                }
            }
            if (!s.accessibility) {
                wc += 620 + rng.nextDouble() * 320;
            }
            wc = Math.max(40, wc);
            totalWaitCluster += wc;

            double baseLf = switch (t.band) {
                case "AM_PEAK" -> 0.84;
                case "PM_PEAK" -> 0.80;
                default -> 0.52;
            };
            double lfC = clamp(baseLf + rng.nextGaussian() * 0.12 + lfNoise[i]
                    + (clusterEffective ? -0.08 : 0), 0.15, 1.3);
            crowdC += lfC * rideMin;
            if (lfC >= 1.0) severeC += rideMin;
        }

        // 簇—周层面的共同运营/天气冲击（同一随机化单元内相关），使少簇设计的不确定性真实偏大
        String cwShockKey = trips.get(0).cwKey;
        double zw = pseudoNormal(cwShockKey + ":wait");
        double zc = pseudoNormal(cwShockKey + ":crowd");
        totalWaitCluster += zw * 38;
        crowdC += zc * 1.6;

        s.firstWait = round1(firstWait);
        s.xferWait = round1(xferWait);
        s.wait = round1(totalWait);
        s.crowd = round1(crowd);
        s.severe = round1(severe);
        s.maxLf = round2(maxLf);
        s.waitCluster = round1(totalWaitCluster);
        s.crowdCluster = round1(crowdC);
        s.severeCluster = round1(severeC);
    }

    private void maybeComplain(long expId, JSpec s, List<TripCtx> trips) {
        double p = 0.0022;
        boolean diverted = false;
        for (TripCtx t : trips) {
            RunState rs = runState.get(t.id);
            if (rs != null && "SHORT_TURN".equals(rs.deviation)) diverted = true;
        }
        if (diverted) p = 0.34;
        if (!s.accessibility) p = 0.22;
        if (s.weather.equals("RAIN")) p += 0.01;
        if (AssignmentEngineHolder.T.equals(trips.get(0).armTrip)
                && isNonExec(trips.get(0))) p += 0.05;

        int count = 0;
        if (rng.nextDouble() < p) count++;
        if (count > 0 && diverted && rng.nextDouble() < 0.2) count++;
        s.complaints = count;
        if (count == 0) return;

        String[] cats = {"WAIT_TIME", "CROWDING", "SERVICE_DEVIATION", "DRIVER_BEHAVIOR"};
        for (int k = 0; k < count; k++) {
            String cat = diverted ? (rng.nextBoolean() ? "SERVICE_DEVIATION" : "CROWDING")
                    : cats[rng.nextInt(cats.length)];
            insertComplaint(expId, s.id, s.code, trips.get(0).route, cat,
                    rng.nextDouble() < 0.25 ? "SERIOUS" : "NORMAL", s.ts);
        }
        jdbc.update("UPDATE card_journey SET complaint_count=? WHERE id=?", count, s.id);
    }

    private void insertComplaint(long expId, Long journeyId, String journeyCode, String route,
                                 String cat, String severity, LocalDateTime serviceTs) {
        // 投诉提交时间故意滞后服务发生 1~6 天，验证统计按 service_event_time 关联
        LocalDateTime complaintTs = serviceTs.plusDays(1 + rng.nextInt(6))
                .withHour(9 + rng.nextInt(10)).withMinute(rng.nextInt(60));
        String code = "CMP-" + journeyCode + "-" + rng.nextInt(100000);
        jdbc.update("""
                INSERT INTO complaint(experiment_id,complaint_code,journey_id,route_code,category,
                    severity,complaint_time,service_event_time,channel)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, expId, code, journeyId, route, cat, severity,
                Timestamp.valueOf(complaintTs), Timestamp.valueOf(serviceTs),
                rng.nextBoolean() ? "APP" : "HOTLINE");
    }

    // ============================ 旅程落库 ============================

    private void insertJourney(long expId, JSpec s, List<TripCtx> trips) {
        TripCtx first = trips.get(0);
        int week = first.week;
        Long id = jdbc.queryForObject("""
                INSERT INTO card_journey(experiment_id,journey_code,week_index,service_date,service_ts,
                    period_band,origin_stop,hub_stop,dest_stop,od_band,route_pattern,weather_class,
                    accessibility,status,passenger_count,anchor_unit_key,anchor_arm_trip,
                    cluster_week_key,cluster_code,anchor_arm_cluster,
                    wait_seconds,first_wait_seconds,transfer_wait_seconds,crowd_exposure,
                    severe_crowd_minutes,max_load_factor,wait_cluster_seconds,
                    crowd_cluster_exposure,severe_crowd_cluster_min,complaint_count,is_demo,note)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                RETURNING id
                """, Long.class,
                expId, s.code, week, java.sql.Date.valueOf(first.date), Timestamp.valueOf(s.ts),
                first.band, s.origin, s.hub, s.dest, s.odBand, s.pattern, s.weather,
                s.accessibility, s.status, s.pax, first.unitKey, first.armTrip,
                first.cwKey, first.clusterCode, first.armCluster,
                s.wait, s.firstWait, s.xferWait == null ? 0 : s.xferWait, s.crowd,
                s.severe, s.maxLf, s.waitCluster, s.crowdCluster, s.severeCluster,
                s.complaints, s.demo, s.note);
        s.id = id;

        int idx = 0;
        for (TripCtx t : trips) {
            RunState rs = runState.get(t.id);
            int legIndex = idx;
            jdbc.update("""
                    INSERT INTO journey_leg(journey_id,leg_index,route_code,trip_id,actual_run_id,
                        board_stop,alight_stop,is_transfer,unit_key,planned_arm,rule_effective,
                        deviation,leg_exposure,wait_seconds,ride_minutes,load_factor,note)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'PENDING', ?,?,?,?)
                    """,
                    id, idx + 1, t.route, t.id, rs == null ? null : rs.runId,
                    idx == 0 ? s.origin : s.hub,
                    idx == trips.size() - 1 ? s.dest : s.hub,
                    idx > 0, t.unitKey, t.armTrip,
                    AssignmentEngineHolder.T.equals(t.armTrip) && rs != null && rs.executed,
                    rs == null ? "NONE" : rs.deviation,
                    0, 0, rs == null ? null : rs.lf, null);
            idx++;
        }
    }

    // ============================ 辅助 ============================

    private JSpec baseSpec(String code, LocalDateTime ts, String pattern, String od, int pax) {
        JSpec s = new JSpec();
        s.code = code;
        s.ts = ts;
        s.pattern = pattern;
        s.odBand = od;
        s.pax = pax;
        // 天气按事件时间关联，而不是按投诉时间
        LocalDate d = ts.toLocalDate();
        s.weather = d.equals(stormDate) ? "RAINSTORM" : d.equals(rainDate) ? "RAIN" : "NORMAL";
        return s;
    }

    private String drawBand() {
        double r = rng.nextDouble();
        return r < 0.42 ? "AM_PEAK" : r < 0.70 ? "PM_PEAK" : "OFF_PEAK";
    }

    private String drawPattern() {
        double r = rng.nextDouble();
        if (r < 0.27) return "R1ONLY";
        if (r < 0.51) return "R2ONLY";
        if (r < 0.85) return "R1R2"; // R1/R2 换乘占比高
        if (r < 0.99) return "R3ONLY";
        return "RX3";
    }

    private String drawOd() {
        double r = rng.nextDouble();
        return r < 0.35 ? "OD_SHORT" : r < 0.75 ? "OD_MID" : "OD_LONG";
    }

    private int hourFor(String band) {
        return switch (band) {
            case "AM_PEAK" -> 7 + rng.nextInt(2);
            case "PM_PEAK" -> 17 + rng.nextInt(2);
            default -> 10 + rng.nextInt(5);
        };
    }

    private TripCtx pick(List<TripCtx> list) {
        if (list == null || list.isEmpty()) return null;
        return list.get(rng.nextInt(list.size()));
    }

    private TripCtx trip(LocalDate date, String route, String tplCode) {
        return tripByKey.get(route + "-" + date + "-" + tplCode);
    }

    private TripCtx requireTripArm(String route, LocalDate date, String bandPrefix, String arm) {
        List<TripCtx> candidates = bandIndex.values().stream()
                .flatMap(List::stream)
                .filter(t -> t.route.equals(route) && t.date.equals(date)
                        && t.tplCode.startsWith(bandPrefix) && arm.equals(t.armTrip))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("找不到符合条件的班次 " + route + " " + date + " " + bandPrefix + " " + arm);
        }
        return candidates.get(0);
    }

    private TripCtx requireAnyTrip(String route, LocalDate date, String bandPrefix) {
        return bandIndex.values().stream()
                .flatMap(List::stream)
                .filter(t -> t.route.equals(route) && t.date.equals(date) && t.tplCode.startsWith(bandPrefix))
                .findFirst().orElseThrow();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 三个确定性均匀量近似标准正态，用于簇—周共同冲击。 */
    private static double pseudoNormal(String key) {
        double u = 0;
        for (int i = 0; i < 3; i++) {
            u += com.ccdev.transit.engine.Deterministic.uniform01("CW-SHOCK-2026", key + "#" + i);
        }
        return (u - 1.5) / 0.5;
    }

    private static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 避免在本类大量写全限定名。 */
    private static final class AssignmentEngineHolder {
        static final String T = AssignmentEngine.TREATMENT;
        static final String C = AssignmentEngine.CONTROL;
    }

    private static final class RunState {
        Long runId;
        boolean executed;
        boolean suspended;
        String deviation = "NONE";
        String shortTurnStop;
        String divertedRoute;
        int delay;
        int headway;
        double lf;
    }

    private static final class JSpec {
        Long id;
        String code;
        LocalDateTime ts;
        String pattern;
        String odBand;
        int pax;
        String origin, hub, dest;
        String weather = "NORMAL";
        boolean accessibility = true;
        String status = "COMPLETE";
        boolean demo;
        String note;
        Double wait, firstWait, xferWait, crowd, severe, maxLf;
        Double waitCluster, crowdCluster, severeCluster;
        int complaints;
    }

    private record TripCtx(
            Long id, String code, String route, LocalDate date, int week, String band,
            String tplCode, String unitKey, String armTrip, String cwKey, String clusterCode,
            String armCluster, LocalDateTime plannedTs) {
    }
}
