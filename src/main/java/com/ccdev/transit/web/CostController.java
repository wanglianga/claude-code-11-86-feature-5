package com.ccdev.transit.web;

import com.ccdev.transit.service.CostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * 试点净运营成本归集 API：成本账、共享资源分摊、每分配单元分项、ITT 成本差与每合格单元净成本区间。
 * 路径同时给出若干语义别名，便于按不同命名习惯对接。
 */
@Tag(name = "试点成本归集")
@RestController
@RequestMapping("/api/experiments/{id}")
public class CostController {

    private final CostService costService;
    private final JdbcTemplate jdbc;

    public CostController(CostService costService, JdbcTemplate jdbc) {
        this.costService = costService;
        this.jdbc = jdbc;
    }

    @Operation(summary = "成本归集完整报告（两方法/对账/ITT/区间/单元分项/样例证据）")
    @GetMapping({"/costs", "/cost", "/cost-report", "/cost-allocation", "/cost-analysis"})
    public Map<String, Object> costs(@PathVariable long id) {
        return costService.reportMap(id);
    }

    @Operation(summary = "成本凭证账（可按 category 过滤）")
    @GetMapping({"/costs/vouchers", "/cost-ledger", "/cost-vouchers"})
    public Map<String, Object> vouchers(@PathVariable long id,
                                       @RequestParam(required = false) String category,
                                       @RequestParam(defaultValue = "200") int limit) {
        List<Map<String, Object>> rows = costService.voucherRows(id, category, limit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("total", rows.size());
        m.put("category", category == null ? "ALL" : category);
        return m;
    }

    @Operation(summary = "结果层：每分配单元 × 方法的成本分项（cost_unit_cost 表）")
    @GetMapping({"/costs/unit-costs", "/cost-unit-costs"})
    public Map<String, Object> unitCosts(@PathVariable long id,
                                         @RequestParam(defaultValue = "DRIVER_ALLOC") String method,
                                         @RequestParam(defaultValue = "5000") int limit) {
        String meth = "DIRECT".equalsIgnoreCase(method) ? "DIRECT" : "DRIVER_ALLOC";
        int lim = Math.max(1, Math.min(10000, limit));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT method,unit_key,block_code,route_code,arm,week_index,
                       overtime,energy,temp_vehicle,maintenance,cancel_loss,
                       direct_amount,allocated_shared,gross_cost,confirmed_offset,net_cost
                FROM cost_unit_cost WHERE experiment_id=? AND method=?
                ORDER BY route_code, week_index, unit_key LIMIT ?
                """, id, meth, lim);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", meth);
        m.put("rows", rows);
        m.put("total", rows.size());
        return m;
    }

    @Operation(summary = "用冻结账本重算成本归集结果（分析师及以上）")
    @PostMapping({"/costs/recompute", "/costs/run", "/cost-recompute"})
    public Map<String, Object> recompute(@PathVariable long id) {
        costService.recompute(id);
        Map<String, Object> full = costService.reportMap(id);
        full.put("ok", true);
        full.put("message", "成本账、共享分摊、单元分项、ITT 与净成本区间已用冻结凭证重新计算");
        return full;
    }

    @Operation(summary = "登记/更新一笔收入或节省抵扣；未确认估计不得冲减净成本（分析师及以上）")
    @PostMapping({"/costs/offsets", "/cost-offset"})
    public Map<String, Object> upsertOffset(@PathVariable long id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String code = str(b, "offsetCode", "offset_code", "code");
        String kind = str(b, "kind", "type");
        String description = str(b, "description", "desc", "memo");
        double amount = num(b, 0.0, "amount", "amountYuan", "amount_yuan", "value", "sum");
        boolean confirmed = bool(b, false, "confirmed", "isConfirmed");
        String ref = str(b, "confirmationRef", "confirmation_ref", "confirmRef", "voucherRef");
        String unitKey = str(b, "unitKey", "unit_key");
        String arm = str(b, "arm");
        String route = str(b, "routeCode", "route_code", "route");
        return costService.upsertOffset(id, code, kind, description, amount, confirmed, ref,
                unitKey, arm, route);
    }

    // ---- 宽松取值：兼容 camelCase / snake_case 与不同命名习惯 ----
    private static String str(Map<String, Object> b, String... keys) {
        for (String k : keys) {
            Object v = b.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static double num(Map<String, Object> b, double dflt, String... keys) {
        for (String k : keys) {
            Object v = b.get(k);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v != null) {
                try {
                    return Double.parseDouble(String.valueOf(v));
                } catch (NumberFormatException ignored) {
                    // try next
                }
            }
        }
        return dflt;
    }

    private static boolean bool(Map<String, Object> b, boolean dflt, String... keys) {
        for (String k : keys) {
            Object v = b.get(k);
            if (v instanceof Boolean bb) {
                return bb;
            }
            if (v != null) {
                String s = String.valueOf(v);
                if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
                if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
            }
        }
        return dflt;
    }
}
