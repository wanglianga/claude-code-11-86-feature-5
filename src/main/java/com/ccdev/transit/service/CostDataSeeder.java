package com.ccdev.transit.service;

import com.ccdev.transit.engine.CostEngine;
import com.ccdev.transit.engine.Deterministic;
import com.ccdev.transit.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 试点成本样例播种（固定种子、幂等、可对旧库回填）。
 *
 * <p>关键样例（题目指定）：
 * <ul>
 *   <li>一辆备用车 {@code COST-TEMP-SPARE-001} 同时支援 R1 处理 AM 班与 R2 对照 AM 班；</li>
 *   <li>一次跨线且跨班维修 {@code COST-MAINT-CROSS-001}（横跨 R1/R2、AM/PM）；</li>
 *   <li>恰好两笔可直接归属的司机加班 {@code COST-OT-001/002}。</li>
 * </ul>
 *
 * <p>另有：每个合格车辆块按冻结公里计量的油电凭证、成对平衡的常规维修、
 * 暴雨停运班次的取消损失、一笔已确认追偿抵扣与一笔被排除的未确认“增收/节省”估计。
 */
@Service
@Order(30)
public class CostDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CostDataSeeder.class);
    private static final String SAMPLE_CODE = SeedService.SAMPLE_CODE;
    private static final String COST_SEED = "TSP-COST-2026-06";

    private final JdbcTemplate jdbc;
    private final CostService costService;

    public CostDataSeeder(JdbcTemplate jdbc, CostService costService) {
        this.jdbc = jdbc;
        this.costService = costService;
    }

    @Override
    public void run(String... args) {
        Long expId;
        try {
            expId = jdbc.queryForObject(
                    "SELECT id FROM experiment WHERE code=?", Long.class, SAMPLE_CODE);
        } catch (Exception e) {
            log.info("成本播种：样例实验尚不存在，跳过（SeedService 之后仍会由重启/回填处理）");
            return;
        }
        if (expId == null) {
            return;
        }
        seedIfMissing(expId);
    }

    /** 幂等：已有成本凭证则只确保结果已重算；否则建车辆块、凭证、抵扣并重算。 */
    public void seedIfMissing(long expId) {
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM cost_voucher WHERE experiment_id=?", Integer.class, expId);
        if (existing != null && existing > 0) {
            Integer unitRows = jdbc.queryForObject(
                    "SELECT count(*) FROM cost_unit_cost WHERE experiment_id=?", Integer.class, expId);
            if (unitRows == null || unitRows == 0) {
                costService.recompute(expId);
            }
            return;
        }

        log.info("开始播种试点成本归集样例 experimentId={}", expId);
        Map<String, BlockInfo> info = ensureBlocks(expId);
        seedVouchers(expId, info);
        seedOffsets(expId, info);
        costService.recompute(expId);
        log.info("试点成本样例播种与归集完成：blocks={} vouchers+offsets", info.size());
    }

    // ============================ 车辆块（720 合格单元 + 1 备用块） ============================

    private record BlockInfo(String unitKey, String blockCode, String route, String arm,
                             LocalDate date, int week, String band, String tpl) {
    }

    private Map<String, BlockInfo> ensureBlocks(long expId) {
        LocalDate start = jdbc.queryForObject(
                "SELECT start_date FROM experiment WHERE id=?", LocalDate.class, expId);
        if (start == null) {
            start = LocalDate.of(2026, 6, 1);
        }

        List<Map<String, Object>> units = jdbc.queryForList("""
                SELECT au.unit_key, au.arm, st.week_index AS trip_week
                FROM assignment_unit au
                LEFT JOIN service_trip st
                  ON st.experiment_id = au.experiment_id AND st.template_key = au.unit_key
                WHERE au.experiment_id=? AND au.design_type='TRIP_RANDOMIZED'
                ORDER BY au.unit_key
                """, expId);

        Map<String, BlockInfo> map = new LinkedHashMap<>();
        for (Map<String, Object> u : units) {
            String key = String.valueOf(u.get("unit_key")); // TRIP:R1:2026-06-02:AM2
            String[] p = key.split(":", 4);
            String route = p[1];
            LocalDate date = LocalDate.parse(p[2]);
            String tpl = p[3];
            String band = tpl.startsWith("AM") ? "AM_PEAK"
                    : tpl.startsWith("PM") ? "PM_PEAK" : "OFF_PEAK";
            int week = u.get("trip_week") == null
                    ? (int) Math.floorDiv(date.toEpochDay() - start.toEpochDay(), 7) + 1
                    : ((Number) u.get("trip_week")).intValue();

            // —— 冻结驱动：车辆公里 / 车辆小时 / 维修工时（播种后不再随结果变动） ——
            double kmBase = switch (route) {
                case "R1" -> 15.2;
                case "R2" -> 12.6;
                case "R3" -> 19.4;
                default -> 13.5;
            };
            double km = r2(kmBase + Deterministic.uniform01(COST_SEED + ":KM", key) * 1.4);

            double hoursBase = "AM_PEAK".equals(band) ? 1.22
                    : "PM_PEAK".equals(band) ? 1.34 : 0.86;
            double routeFactor = switch (route) {
                case "R1" -> 1.0;
                case "R2" -> 0.93;
                case "R3" -> 1.24;
                default -> 1.0;
            };
            double hours = r2(hoursBase * routeFactor
                    + (Deterministic.uniform01(COST_SEED + ":HR", key) - 0.5) * 0.10);

            double maintHours = r2(0.05 + km * 0.012
                    + Deterministic.uniform01(COST_SEED + ":MX", key) * 0.22);

            String blockCode = "VB-" + route + "-" + date + "-" + tpl;
            String arm = u.get("arm") == null ? null : String.valueOf(u.get("arm"));

            jdbc.update("""
                    INSERT INTO vehicle_block(experiment_id,block_code,unit_key,route_code,
                        service_date,week_index,period_band,arm,block_kind,eligible,
                        frozen_vehicle_hours,frozen_vehicle_km,frozen_maintenance_hours)
                    VALUES (?,?,?,?,?,?,?,?, 'REGULAR', TRUE, ?,?,?)
                    ON CONFLICT (experiment_id,block_code) DO NOTHING
                    """, expId, blockCode, key, route, java.sql.Date.valueOf(date), week, band, arm,
                    hours, km, maintHours);

            map.put(key, new BlockInfo(key, blockCode, route, arm, date, week, band, tpl));
        }

        // 跨线备用车自身的非合格资源块（成本通过共享凭证 + support_links 追溯到两个受援块）
        jdbc.update("""
                INSERT INTO vehicle_block(experiment_id,block_code,unit_key,route_code,
                    service_date,week_index,period_band,arm,block_kind,eligible,
                    frozen_vehicle_hours,frozen_vehicle_km,frozen_maintenance_hours)
                VALUES (?,'VB-SPARE-R1R2-20260602',NULL,'MULTI',?,1,'AM_PEAK',NULL,'SPARE',FALSE,
                    8.0,64.0,0.5)
                ON CONFLICT (experiment_id,block_code) DO NOTHING
                """, expId, java.sql.Date.valueOf(LocalDate.of(2026, 6, 2)));

        return map;
    }

    // ============================ 凭证账 ============================

    private void seedVouchers(long expId, Map<String, BlockInfo> info) {
        // 著名短线折返单元（R1 处理）与承接分流的 R2 对照单元：优先从实际运行偏离反查，保证同一样例
        String shortTurn = firstString("""
                SELECT st.template_key FROM actual_service_run r
                JOIN service_trip st ON st.id=r.trip_id
                WHERE st.experiment_id=? AND r.deviation='SHORT_TURN' LIMIT 1
                """, expId);
        String divertR2 = firstString("""
                SELECT st.template_key FROM actual_service_run r
                JOIN service_trip st ON st.id=r.trip_id
                WHERE st.experiment_id=? AND st.route_code='R2'
                  AND st.service_date=DATE '2026-06-02'
                ORDER BY r.observed_lf DESC NULLS LAST LIMIT 1
                """, expId);
        if (shortTurn == null || !info.containsKey(shortTurn)) {
            shortTurn = findUnit(info, "R1", LocalDate.of(2026, 6, 2), "AM", "TREATMENT");
        }
        if (divertR2 == null || !info.containsKey(divertR2)) {
            divertR2 = findUnit(info, "R2", LocalDate.of(2026, 6, 2), "AM", "CONTROL");
        }

        // ---- 1) 油电消耗：每个合格块一张实际计量凭证（直接归属，按冻结公里计价） ----
        for (BlockInfo b : info.values()) {
            double km = blockKm(expId, b.blockCode());
            double rate = switch (b.route()) {
                case "R1" -> 3.62;
                case "R2" -> 3.44;
                case "R3" -> 2.95;
                default -> 3.30;
            };
            double factor = 0.92 + Deterministic.uniform01(COST_SEED + ":EN", b.unitKey()) * 0.16;
            double amount = r2(km * rate * factor);
            voucher(expId, "COST-EN-" + b.route() + "-" + b.date() + "-" + b.tpl(),
                    CostEngine.ENERGY, "DIRECT", amount, b, null, null,
                    "KM-METER-" + b.route() + "-" + b.date() + "-" + b.tpl(),
                    "车辆块实际油/电消耗计量（按冻结公里与线路能耗单价入账）",
                    false, false, null);
        }

        // ---- 2) 两笔司机加班（题目指定，恰好两笔，均可直接归属，均在处理臂） ----
        BlockInfo ot1 = info.get(shortTurn);
        voucher(expId, "COST-OT-001", CostEngine.OVERTIME, "DIRECT", 900.00, ot1, null, null,
                "OT-20260602-R1-07",
                "R1 处理 AM 班短线折返、清客与接续导致司机加班（实际加班记录与加班费率单据，非平均工资率估算）",
                false, false, null);

        String ot2Unit = findUnit(info, "R2", LocalDate.of(2026, 6, 3), "AM", "TREATMENT");
        BlockInfo ot2 = info.get(ot2Unit);
        voucher(expId, "COST-OT-002", CostEngine.OVERTIME, "DIRECT", 700.00, ot2, null, null,
                "OT-20260603-R2-12",
                "加密间隔后临时延长高峰运营，司机加班（实际加班与加班费率单据）",
                false, false, null);

        // ---- 3) 一辆备用车同时支援 R1 处理班 + R2 对照班（共享，直接法留未归属） ----
        List<CostEngine.SupportRef> spareRefs = List.of(
                ref(info, shortTurn, "实际支援 R1 处理 AM 班：短线折返后补位/疏运"),
                ref(info, divertR2, "实际支援 R2 对照 AM 班：承接折返分流、加密疏运"));
        voucher(expId, "COST-TEMP-SPARE-001", CostEngine.TEMP_VEHICLE, "SHARED", 1800.00,
                null, CostEngine.HOURS, "SPARE_VEHICLE", "INV-SPARE-20260602",
                "备用车跨线同时支援 R1 处理班与 R2 对照班的待命+调派账单（一张凭证，禁止拆成两张重复入账）",
                true, false, spareRefs);
        // 凭证自身绑定到备用车资源块；实际支援对象另由 support_links 追溯到 R1T/R2C 两个车辆块
        jdbc.update("""
                UPDATE cost_voucher SET block_code='VB-SPARE-R1R2-20260602',
                    block_id=(SELECT id FROM vehicle_block
                              WHERE experiment_id=? AND block_code='VB-SPARE-R1R2-20260602')
                WHERE experiment_id=? AND voucher_code='COST-TEMP-SPARE-001'
                """, expId, expId);

        // ---- 4) 一次跨线且跨班维修（共享，直接法留未归属；按冻结维修工时分摊） ----
        String mx1 = findUnit(info, "R1", LocalDate.of(2026, 6, 3), "AM", "TREATMENT");
        String mx2 = findUnit(info, "R1", LocalDate.of(2026, 6, 3), "PM", "CONTROL");
        String mx3 = findUnit(info, "R2", LocalDate.of(2026, 6, 4), "AM", "CONTROL");
        String mx4 = findUnit(info, "R2", LocalDate.of(2026, 6, 5), "PM", "TREATMENT");
        List<CostEngine.SupportRef> mxRefs = new ArrayList<>();
        mxRefs.add(ref(info, mx1, "R1 AM 处理班受电/走行部复检"));
        mxRefs.add(ref(info, mx2, "R1 PM 对照班跨班续修完工"));
        mxRefs.add(ref(info, mx3, "R2 AM 对照班同批次轮对/制动复核"));
        mxRefs.add(ref(info, mx4, "R2 PM 处理班出场前复检"));
        voucher(expId, "COST-MAINT-CROSS-001", CostEngine.MAINTENANCE, "SHARED", 2600.00,
                null, CostEngine.MAINT_HOURS, "CROSS_LINE_MAINT", "WO-CROSS-20260603",
                "中心枢纽共用抢修组一次跨 R1/R2、横跨 AM/PM 班的跨线跨班维修工单（共同成本，不武断拆分）",
                true, true, mxRefs);

        // ---- 5) 四笔常规直接维修：两对 T/C 同额配对，避免在 ITT 中引入系统性偏向 ----
        String rm1t = findUnit(info, "R1", LocalDate.of(2026, 6, 4), "AM", "TREATMENT");
        String rm1c = findUnit(info, "R1", LocalDate.of(2026, 6, 4), "AM", "CONTROL");
        voucher(expId, "COST-MAINT-D-001T", CostEngine.MAINTENANCE, "DIRECT", 620.00,
                info.get(rm1t), null, null, "WO-R1-0604-T", "R1 AM 处理块常规例修（直接归属）", false, false, null);
        voucher(expId, "COST-MAINT-D-001C", CostEngine.MAINTENANCE, "DIRECT", 620.00,
                info.get(rm1c), null, null, "WO-R1-0604-C", "R1 AM 对照块同项常规例修（配对，直接归属）", false, false, null);

        String rm2t = findUnit(info, "R2", LocalDate.of(2026, 6, 9), "PM", "TREATMENT");
        String rm2c = findUnit(info, "R2", LocalDate.of(2026, 6, 9), "PM", "CONTROL");
        voucher(expId, "COST-MAINT-D-002T", CostEngine.MAINTENANCE, "DIRECT", 760.00,
                info.get(rm2t), null, null, "WO-R2-0609-T", "R2 PM 处理块常规保养（直接归属）", false, false, null);
        voucher(expId, "COST-MAINT-D-002C", CostEngine.MAINTENANCE, "DIRECT", 760.00,
                info.get(rm2c), null, null, "WO-R2-0609-C", "R2 PM 对照块同项保养（配对，直接归属）", false, false, null);

        // ---- 6) 取消班次损失：暴雨停运的 R1/R2 AM 班次（每块一张，直接归属） ----
        List<Map<String, Object>> suspended = jdbc.queryForList("""
                SELECT st.template_key AS unit_key FROM actual_service_run r
                JOIN service_trip st ON st.id=r.trip_id
                WHERE st.experiment_id=? AND r.deviation='SUSPENDED'
                ORDER BY st.template_key
                """, expId);
        int ci = 0;
        for (Map<String, Object> row : suspended) {
            String unitKey = String.valueOf(row.get("unit_key"));
            BlockInfo b = info.get(unitKey);
            if (b == null) {
                continue;
            }
            ci++;
            double amount = r2(480 + Deterministic.uniform01(COST_SEED + ":CANCEL", unitKey) * 220);
            voucher(expId, String.format("COST-CANCEL-%03d", ci), CostEngine.CANCEL_LOSS,
                    "DIRECT", amount, b, null, null, "CXL-" + b.date() + "-" + b.tpl(),
                    "停运取消班次的乘客补偿与不可退乘务/车场待命损失（实际单据；不重复计列票收损失）",
                    false, false, null);
        }
    }

    // ============================ 收入/节省抵扣 ============================

    private void seedOffsets(long expId, Map<String, BlockInfo> info) {
        String shortTurn = firstString("""
                SELECT st.template_key FROM actual_service_run r
                JOIN service_trip st ON st.id=r.trip_id
                WHERE st.experiment_id=? AND r.deviation='SHORT_TURN' LIMIT 1
                """, expId);

        // 已确认、有贷记/追偿凭证号：允许冲减净成本（小额，不填平或抵消加班信号）
        jdbc.update("""
                INSERT INTO cost_offset(experiment_id,offset_code,kind,description,amount,confirmed,
                    confirmation_ref,unit_key,arm,route_code,service_date)
                VALUES (?,'OFF-CONF-001','CLAIM','备用车场损保险小额追偿（已到账，有贷记凭证）',
                    400.00,TRUE,'CN-202606-7781',?, 'TREATMENT','R1',DATE '2026-06-05')
                ON CONFLICT (experiment_id,offset_code) DO NOTHING
                """, expId, shortTurn);

        // 未确认估计：金额故意很大（若错误计入会把净成本填成“净节省”），必须排除
        jdbc.update("""
                INSERT INTO cost_offset(experiment_id,offset_code,kind,description,amount,confirmed,
                    confirmation_ref,unit_key,arm,route_code,service_date)
                VALUES (?,'OFF-EST-UNCONF-001','REVENUE',
                    '运营方自报“准点提升带来的票价增收+用工节省”估算，尚无清算/凭证，不得用于填平成本',
                    68000.00,FALSE,NULL,NULL,NULL,NULL,DATE '2026-06-28')
                ON CONFLICT (experiment_id,offset_code) DO NOTHING
                """, expId);
    }

    // ============================ 落库辅助 ============================

    private void voucher(long expId, String code, String category, String attribution,
                         double amount, BlockInfo directBlock, String driverBasis, String pool,
                         String sourceRef, String description, boolean crossLine, boolean crossShift,
                         List<CostEngine.SupportRef> supports) {
        String unitKey = directBlock == null ? null : directBlock.unitKey();
        String blockCode = directBlock == null ? null : directBlock.blockCode();
        String route = directBlock == null ? "MULTI" : directBlock.route();
        LocalDate date = directBlock == null ? LocalDate.of(2026, 6, 2) : directBlock.date();
        Integer week = directBlock == null ? 1 : directBlock.week();
        String linksJson = supports == null ? null : Json.write(supports);

        jdbc.update("""
                INSERT INTO cost_voucher(experiment_id,voucher_code,cost_category,attribution,
                    driver_basis,shared_pool,block_code,unit_key,route_code,service_date,week_index,
                    amount,currency,source_ref,description,cross_line,cross_shift,support_links)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'CNY',?,?,?,?,?::jsonb)
                ON CONFLICT (experiment_id,voucher_code) DO NOTHING
                """,
                expId, code, category, attribution, driverBasis, pool, blockCode, unitKey, route,
                java.sql.Date.valueOf(date), week, BigDecimal.valueOf(amount),
                sourceRef, description, crossLine, crossShift, linksJson);
    }

    private CostEngine.SupportRef ref(Map<String, BlockInfo> info, String unitKey, String detail) {
        BlockInfo b = info.get(unitKey);
        if (b == null) {
            throw new IllegalStateException("成本播种找不到单元: " + unitKey);
        }
        return new CostEngine.SupportRef(b.unitKey(), b.blockCode(), b.route(), b.arm(), detail);
    }

    /** 在内存单元表中按 线路/日期/模板前缀/臂 找一个确定单元。 */
    private String findUnit(Map<String, BlockInfo> info, String route, LocalDate date,
                            String tplPrefix, String arm) {
        return info.values().stream()
                .filter(b -> b.route().equals(route) && b.date().equals(date)
                        && b.tpl().startsWith(tplPrefix) && arm.equals(b.arm()))
                .map(BlockInfo::unitKey)
                .sorted()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "找不到成本播种所需单元 " + route + " " + date + " " + tplPrefix + " " + arm));
    }

    private String firstString(String sql, long expId) {
        List<String> rows = jdbc.queryForList(sql, String.class, expId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private double blockKm(long expId, String blockCode) {
        Double km = jdbc.queryForObject(
                "SELECT frozen_vehicle_km FROM vehicle_block WHERE experiment_id=? AND block_code=?",
                Double.class, expId, blockCode);
        return km == null ? 13.0 : km;
    }

    private static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
