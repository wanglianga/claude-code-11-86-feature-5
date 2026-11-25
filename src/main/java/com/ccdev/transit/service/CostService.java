package com.ccdev.transit.service;

import com.ccdev.transit.engine.CostEngine;
import com.ccdev.transit.engine.CostEngine.Block;
import com.ccdev.transit.engine.CostEngine.Offset;
import com.ccdev.transit.engine.CostEngine.Report;
import com.ccdev.transit.engine.CostEngine.SupportRef;
import com.ccdev.transit.engine.CostEngine.Voucher;
import com.ccdev.transit.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本归集结果层：从 PostgreSQL 读取车辆块/凭证/抵扣，调用纯 {@link CostEngine} 计算，
 * 并把“每分配单元 × 方法”的成本分项幂等落库到 cost_unit_cost。
 * GET 报告始终由引擎按当前账本实时计算，避免读到陈旧结果。
 */
@Service
public class CostService {

    private final JdbcTemplate jdbc;
    private final CostEngine engine = new CostEngine();

    public CostService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ============================ 读取 + 计算 ============================

    public Report report(long experimentId) {
        return engine.calculate(loadBlocks(experimentId), loadVouchers(experimentId),
                loadOffsets(experimentId));
    }

    /** 直接返回可序列化的 snake_case Map，并附上原始凭证账/抵扣行，供一次取全。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> reportMap(long experimentId) {
        Report r = report(experimentId);
        Map<String, Object> root = Json.MAPPER.convertValue(r,
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        // 保持顺序：把账本凭证明细放到最前的若干键之后也无妨，这里直接追加
        root.put("voucher_ledger", voucherRows(experimentId, null, 10000));
        root.put("offset_rows", jdbc.queryForList(
                "SELECT offset_code,kind,description,amount,confirmed,confirmation_ref,"
                        + "unit_key,arm,route_code,service_date FROM cost_offset "
                        + "WHERE experiment_id=? ORDER BY id", experimentId));
        root.put("currency_note", "金额单位：人民币元（CNY）；引擎内部按分精确守恒计算");
        return root;
    }

    public List<Map<String, Object>> voucherRows(long experimentId, String category, int limit) {
        int lim = Math.max(1, Math.min(10000, limit));
        if (category != null && !category.isBlank() && !"ALL".equals(category)) {
            return jdbc.queryForList("""
                    SELECT voucher_code,cost_category,attribution,driver_basis,shared_pool,
                           block_code,unit_key,route_code,service_date,week_index,amount,currency,
                           source_ref,description,cross_line,cross_shift,support_links
                    FROM cost_voucher WHERE experiment_id=? AND cost_category=?
                    ORDER BY attribution DESC, id LIMIT ?
                    """, experimentId, category, lim);
        }
        return jdbc.queryForList("""
                SELECT voucher_code,cost_category,attribution,driver_basis,shared_pool,
                       block_code,unit_key,route_code,service_date,week_index,amount,currency,
                       source_ref,description,cross_line,cross_shift,support_links
                FROM cost_voucher WHERE experiment_id=?
                ORDER BY attribution DESC, cost_category, id LIMIT ?
                """, experimentId, lim);
    }

    // ============================ 重算并落库单元结果 ============================

    @Transactional
    public Report recompute(long experimentId) {
        Report r = report(experimentId);
        jdbc.update("DELETE FROM cost_unit_cost WHERE experiment_id=?", experimentId);

        for (CostEngine.UnitLine u : r.units()) {
            insertUnitRow(experimentId, CostEngine.DIRECT, u,
                    u.directCostByCategory(), 0.0, u.directAmount(), u.netCostDirect());

            Map<String, Double> combined = new LinkedHashMap<>();
            for (String cat : CostEngine.CATEGORIES) {
                combined.put(cat,
                        nz(u.directCostByCategory().get(cat)) + nz(u.allocatedSharedCostByCategory().get(cat)));
            }
            insertUnitRow(experimentId, CostEngine.DRIVER_ALLOC, u, combined,
                    u.allocatedSharedAmount(), u.grossCostAlloc(), u.netCostAlloc());
        }
        return r;
    }

    private void insertUnitRow(long expId, String method, CostEngine.UnitLine u,
                               Map<String, Double> categoryAmounts, double allocatedShared,
                               double gross, double net) {
        jdbc.update("""
                INSERT INTO cost_unit_cost(experiment_id,method,unit_key,block_code,route_code,arm,
                    week_index,overtime,energy,temp_vehicle,maintenance,cancel_loss,
                    direct_amount,allocated_shared,gross_cost,confirmed_offset,net_cost)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                expId, method, u.unitKey(), u.blockCode(), u.routeCode(), u.arm(), u.weekIndex(),
                categoryAmounts.getOrDefault(CostEngine.OVERTIME, 0.0),
                categoryAmounts.getOrDefault(CostEngine.ENERGY, 0.0),
                categoryAmounts.getOrDefault(CostEngine.TEMP_VEHICLE, 0.0),
                categoryAmounts.getOrDefault(CostEngine.MAINTENANCE, 0.0),
                categoryAmounts.getOrDefault(CostEngine.CANCEL_LOSS, 0.0),
                u.directAmount(), allocatedShared, gross, u.confirmedOffset(), net);
    }

    // ============================ 抵扣写入（验证“不得用未确认估计填平”） ============================

    @Transactional
    public Map<String, Object> upsertOffset(long expId, String offsetCode, String kind,
                                            String description, double amountYuan,
                                            boolean confirmed, String confirmationRef,
                                            String unitKey, String arm, String routeCode) {
        if (amountYuan < 0) {
            throw new IllegalArgumentException("抵扣金额不能为负");
        }
        if (confirmed && (confirmationRef == null || confirmationRef.isBlank())) {
            // 关键控制点：声称“已确认”却没有确认凭证号，一律拒绝，防止用估计填平成本
            throw new IllegalArgumentException(
                    "confirmed=true 的收入/节省必须提供 confirmation_ref（清算/贷记/追偿凭证号）；"
                            + "无凭证的估计只能以 confirmed=false 备忘，不能冲减净成本");
        }
        String code = (offsetCode == null || offsetCode.isBlank())
                ? "OFF-" + java.util.UUID.randomUUID() : offsetCode;
        String k = (kind == null || kind.isBlank()) ? "SAVING" : kind;

        jdbc.update("""
                INSERT INTO cost_offset(experiment_id,offset_code,kind,description,amount,confirmed,
                    confirmation_ref,unit_key,arm,route_code)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (experiment_id,offset_code) DO UPDATE SET
                    kind=EXCLUDED.kind, description=EXCLUDED.description, amount=EXCLUDED.amount,
                    confirmed=EXCLUDED.confirmed, confirmation_ref=EXCLUDED.confirmation_ref,
                    unit_key=EXCLUDED.unit_key, arm=EXCLUDED.arm, route_code=EXCLUDED.route_code
                """, expId, code, k, description, BigDecimal.valueOf(amountYuan),
                confirmed, confirmed ? confirmationRef : null,
                blankToNull(unitKey), blankToNull(arm), blankToNull(routeCode));

        Report r = recompute(expId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("offset_code", code);
        out.put("confirmed", confirmed);
        out.put("reconciliation", r.reconciliation());
        out.put("net_operating_cost_total", r.directAttribution().netOperatingCostTotal());
        out.put("excluded_unconfirmed_estimate_total",
                r.offsets().excludedUnconfirmedEstimateTotal());
        out.put("direct_itt", r.directAttribution().ittCostDifferencePerUnit());
        out.put("driver_alloc_itt", r.driverAllocation().ittCostDifferencePerUnit());
        return out;
    }

    // ============================ 加载 ============================

    private List<Block> loadBlocks(long expId) {
        return jdbc.query("""
                SELECT unit_key,block_code,route_code,arm,week_index,eligible,
                       frozen_vehicle_hours,frozen_vehicle_km,frozen_maintenance_hours
                FROM vehicle_block WHERE experiment_id=? ORDER BY id
                """, (rs, i) -> new Block(
                rs.getString("unit_key"),
                rs.getString("block_code"),
                rs.getString("route_code"),
                rs.getString("arm"),
                rs.getInt("week_index"),
                rs.getBoolean("eligible"),
                rs.getDouble("frozen_vehicle_hours"),
                rs.getDouble("frozen_vehicle_km"),
                rs.getDouble("frozen_maintenance_hours")), expId);
    }

    private List<Voucher> loadVouchers(long expId) {
        return jdbc.query("""
                SELECT voucher_code,cost_category,amount,attribution,driver_basis,shared_pool,
                       unit_key,route_code,cross_line,cross_shift,support_links
                FROM cost_voucher WHERE experiment_id=? ORDER BY id
                """, (rs, i) -> {
            String linksJson = rs.getString("support_links");
            List<SupportRef> links = null;
            if (linksJson != null && !linksJson.isBlank()) {
                try {
                    links = List.of(Json.read(linksJson, SupportRef[].class));
                } catch (Exception e) {
                    links = new ArrayList<>();
                }
            }
            return new Voucher(
                    rs.getString("voucher_code"),
                    rs.getString("cost_category"),
                    toCents(rs.getBigDecimal("amount")),
                    "SHARED".equals(rs.getString("attribution")),
                    rs.getString("unit_key"),
                    rs.getString("route_code"),
                    rs.getString("shared_pool"),
                    rs.getString("driver_basis"),
                    rs.getBoolean("cross_line"),
                    rs.getBoolean("cross_shift"),
                    links);
        }, expId);
    }

    private List<Offset> loadOffsets(long expId) {
        return jdbc.query("""
                SELECT offset_code,kind,amount,confirmed,unit_key,arm,route_code
                FROM cost_offset WHERE experiment_id=? ORDER BY id
                """, (rs, i) -> new Offset(
                rs.getString("offset_code"),
                rs.getString("kind"),
                toCents(rs.getBigDecimal("amount")),
                rs.getBoolean("confirmed"),
                rs.getString("unit_key"),
                rs.getString("arm"),
                rs.getString("route_code")), expId);
    }

    static long toCents(BigDecimal bd) {
        if (bd == null) {
            return 0L;
        }
        return bd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static double nz(Double d) {
        return d == null ? 0.0 : d;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
