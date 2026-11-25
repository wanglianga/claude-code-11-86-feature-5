#!/usr/bin/env bash
# 灰度实验服务关键不变量验证。
# 用法：./verify.sh [BASE_URL]
# 依赖：bash、curl、python3（仅用标准库）
set -u
BASE="${1:-${BASE_URL:-http://127.0.0.1:8080}}"
PYBIN="$(command -v python3 || true)"
if [ -z "$PYBIN" ] && [ -x /opt/python-3.12/bin/python3 ]; then PYBIN=/opt/python-3.12/bin/python3; fi
[ -n "$PYBIN" ] || { echo "未找到 python3"; exit 1; }

echo "==> 等待服务健康：$BASE"
for i in $(seq 1 60); do
  if curl -fsS --max-time 3 "$BASE/api/health" 2>/dev/null | grep -q '"db":"UP"'; then
    echo "    health UP"; break
  fi
  sleep 2
  if [ "$i" = "60" ]; then echo "服务未就绪"; exit 1; fi
done

BASE="$BASE" "$PYBIN" - <<'PY'
import base64, json, os, sys, urllib.request, urllib.error

BASE = os.environ["BASE"].rstrip("/")
fails, passes = [], []

def req(method, path, user="analyst", pwd="analyst123", body=None, raw=False):
    r = urllib.request.Request(BASE + path, method=method)
    token = base64.b64encode(f"{user}:{pwd}".encode()).decode()
    r.add_header("Authorization", "Basic " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, data=data, timeout=20) as resp:
            txt = resp.read().decode()
            return resp.status, (txt if raw else json.loads(txt or "{}"))
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt

def check(name, cond, detail=""):
    (passes if cond else fails).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + ("" if cond or not detail else f"  -> {detail}"))

# 1. 实验存在
st, exp = req("GET", "/api/experiments/by-code/PILOT-R123-202606")
check("样例实验已登记", st == 200 and exp.get("id"), str(exp)[:200])
eid = exp["id"]

# 2. 两套分配严格 50%
for design, total in [("TRIP_RANDOMIZED", 720), ("CLUSTER_WEEK", 8)]:
    st, a = req("GET", f"/api/experiments/{eid}/assignments?design={design}")
    check(f"{design} 总数={total}", a.get("total") == total, str(a.get("total")))
    check(f"{design} 处理/对照各半", a.get("treatment") == total // 2
          and a.get("control") == total // 2
          and abs(a.get("treatmentShare", 0) - 0.5) < 1e-9,
          f"{a.get('treatment')}/{a.get('control')} share={a.get('treatmentShare')}")

# 3. 命名旅程完整路径标记
expected = {
    "J-DEMO-DIVERT-01": ("CROSS_MIXED", "TREATMENT", True, True),
    "J-DEMO-PURER2-01": ("FULL_CONTROL", "CONTROL", False, False),
    "J-DEMO-CROSS-01":  ("CROSS_MIXED", "CONTROL", True, False),
    "J-DEMO-FULLT-01":  ("FULL_TREATMENT", "TREATMENT", False, False),
    "J-DEMO-FULLC-01":  ("FULL_CONTROL", "CONTROL", False, False),
    "J-DEMO-NONEXEC-01": ("FULL_CONTROL", "TREATMENT", False, False),
    "J-DEMO-INCOMPLETE-01": ("UNKNOWN", None, False, False),
    "J-DEMO-MIDCHANGE-01": ("UNKNOWN", None, False, False),
    "J-DEMO-STORM-01": ("UNKNOWN", None, False, False),
}
for code, (path, arm, cross, diverted) in expected.items():
    st, d = req("GET", f"/api/experiments/{eid}/journeys/{code}")
    led = d.get("ledger") or {}
    ok = st == 200 and led.get("path_label") == path
    if arm:
        ok &= led.get("assigned_arm_trip") == arm
    if cross:
        ok &= bool(led.get("cross_line"))
    if diverted:
        ok &= bool(led.get("diverted"))
    check(f"{code} => {path}" + (f"/{arm}" if arm else ""), ok,
          f"got {led.get('path_label')} arm={led.get('assigned_arm_trip')} "
          f"cross={led.get('cross_line')} divert={led.get('diverted')}")

# 4. 交叉暴露不混入纯净对照
st, fc = req("GET", f"/api/experiments/{eid}/exposure?pathLabel=FULL_CONTROL&limit=500")
rows = fc.get("rows", [])
bad = [r["journey_code"] for r in rows if r.get("cross_line")]
check("FULL_CONTROL 中没有任何 cross_line=true 旅程", not bad, str(bad[:5]))
check("CROSS_MIXED 数量 > 0",
      any(c["path_label"] == "CROSS_MIXED" and c["n"] > 50 for c in fc.get("counts", [])))

# 5. 分析结果：两设计 ITT + RI + CACE
st, an = req("GET", f"/api/experiments/{eid}/analysis")
res = an.get("results", [])
def find(**kw):
    for r in res:
        if all(r.get(k) == v for k, v in kw.items()):
            return r
    return None
itt_t = find(design_type="TRIP_RANDOMIZED", estimator_code="ITT",
             metric_code="wait_seconds", scope="COMMON_SUPPORT")
itt_c = find(design_type="CLUSTER_WEEK", estimator_code="ITT",
             metric_code="wait_seconds", scope="COMMON_SUPPORT")
check("班次方案 ITT(候车) 存在且为负效应", bool(itt_t) and itt_t["estimate"] < 0, str(itt_t))
check("簇周方案 ITT(候车) 存在且为负、效应更大",
      bool(itt_c) and itt_c["estimate"] < itt_t["estimate"], str(itt_c and itt_c["estimate"]))
check("簇周方案 95% 区间宽于班次方案",
      (itt_c["ci_high"] - itt_c["ci_low"]) > (itt_t["ci_high"] - itt_t["ci_low"]),
      f"cluster width={itt_c['ci_high']-itt_c['ci_low']:.1f} vs "
      f"trip width={itt_t['ci_high']-itt_t['ci_low']:.1f}")
ri = find(design_type="CLUSTER_WEEK", estimator_code="RI_PERMUTATION")
check("簇周随机化推断 RI p 值存在（16 置换）", bool(ri) and 0 < ri["p_value"] <= 1)
cace = find(estimator_code="CACE_WALD")
check("CACE/Wald 估计存在", bool(cace) and cace["estimate"] < 0)
crossr = find(estimator_code="CROSS_RATE")
check("跨线暴露率带 Wilson 区间", bool(crossr) and crossr["ci_low"] < crossr["estimate"] < crossr["ci_high"])

# 6. 共同支持缺口存在（暴雨层不能外推）
st, sup = req("GET", f"/api/experiments/{eid}/support")
check("识别出无共同支持的缺口层", int(sup["gaps"]["gap_strata_trip"]) > 0
      or int(sup["gaps"]["gap_strata_cluster"]) > 0, str(sup["gaps"]))

# 7. 护栏：投诉与滞留超限
st, gg = req("GET", f"/api/experiments/{eid}/guardrails")
latest = {g["rule_code"]: g for g in gg.get("latest", [])}
check("投诉护栏超限", latest.get("COMPLAINT_RATE_MAX", {}).get("breached") is True)
check("滞留/停运安全护栏超限", latest.get("STRANDING_RATE_MAX", {}).get("breached") is True)
cmp_rule = latest.get("COMPLAINT_RATE_MAX", {})
cmp_detail = cmp_rule.get("detail", {})
if isinstance(cmp_detail, str):
    cmp_detail = json.loads(cmp_detail)
check("投诉按 service_event_time 关联且存在滞后提交",
      cmp_detail.get("timeBasis", "").startswith("service_event_time")
      and int(cmp_detail.get("complaintsLoggedOnLaterDate", 0)) > 0)

# 8. 权限：analyst 不能执行扩围结论
st, _ = req("POST", f"/api/experiments/{eid}/expansion/evaluate", user="analyst")
check("analyst 调用扩围评估被 403 拒绝", st == 403, f"status={st}")

# 9. approver 评估 -> BLOCKED；人工 GO 被拒
st, dec = req("POST", f"/api/experiments/{eid}/expansion/evaluate", user="approver", pwd="approver123")
check("approver 评估后结论为 BLOCKED", st == 200 and dec.get("decision") == "BLOCKED",
      f"status={st} decision={dec.get('decision') if isinstance(dec,dict) else dec}")
st, body = req("POST", f"/api/experiments/{eid}/expansion/decide",
               user="approver", pwd="approver123",
               body={"decision": "GO", "reason": "try override"})
check("护栏超限时人工 GO 被服务端拒绝(409)", st == 409, f"status={st}")

# 10. 试点成本归集：两归属路线 / 不重复计费 / 未归属余额 / 净成本区间 / 抵扣纪律
st, costs = req("GET", f"/api/experiments/{eid}/costs")
check("成本报告 /costs 200 且含两方法",
      st == 200 and isinstance(costs, dict)
      and "direct_attribution" in costs and "driver_allocation" in costs, str(st))

if isinstance(costs, dict) and costs.get("reconciliation"):
    rec = costs["reconciliation"]; ev = costs.get("sample_evidence", {}) or {}
    off = costs.get("offsets", {}) or {}
    dm = costs["direct_attribution"]; am = costs["driver_allocation"]
    check("成本：720 合格单元且 360/360",
          costs.get("qualified_service_units") == 720
          and costs.get("treatment_units") == 360 and costs.get("control_units") == 360)
    check("成本：样例恰好两笔司机加班", ev.get("overtime_record_count") == 2)
    spare_v = ev.get("spare_vehicle_voucher") or {}
    spare_arms = {s.get("arm") for s in (spare_v.get("support_links") or [])}
    spare_routes = {s.get("route_code") for s in (spare_v.get("support_links") or [])}
    check("成本：一辆备用车同时支援 R1处理班+R2对照班",
          ev.get("spare_vehicle_present") and ev.get("spare_serves_treatment_and_control")
          and {"TREATMENT", "CONTROL"} <= spare_arms and {"R1", "R2"} <= spare_routes,
          f"arms={spare_arms} routes={spare_routes}")
    mx = ev.get("cross_line_maintenance_voucher") or {}
    check("成本：一次跨线且跨班维修",
          ev.get("cross_line_maintenance_present") and mx.get("cross_line") and mx.get("cross_shift"))

    ledger = costs.get("voucher_ledger", []) or []
    check("成本：凭证唯一、不重复且凭证数=账内行数",
          rec.get("no_double_billing") is True
          and rec.get("distinct_voucher_count") == len(ledger) and len(ledger) >= 736,
          f"distinct={rec.get('distinct_voucher_count')} rows={len(ledger)}")
    check("成本：OVERTIME 凭证恰好 2 张",
          len([v for v in ledger if v.get("cost_category") == "OVERTIME"]) == 2)
    sp = [v for v in ledger if v.get("voucher_code") == "COST-TEMP-SPARE-001"]
    check("成本：备用车凭证 1800 且为 SHARED（不拆单）",
          len(sp) == 1 and abs(float(sp[0]["amount"]) - 1800.0) < 0.01
          and sp[0].get("attribution") == "SHARED")
    mxrow = [v for v in ledger if v.get("voucher_code") == "COST-MAINT-CROSS-001"]
    check("成本：跨线维修凭证 2600 且 SHARED",
          len(mxrow) == 1 and abs(float(mxrow[0]["amount"]) - 2600.0) < 0.01
          and mxrow[0].get("attribution") == "SHARED")

    check("成本：直接法平衡 已归属+未归属=凭证总额",
          rec.get("direct_balanced") is True
          and abs(float(rec.get("direct_plus_unallocated_difference", 9))) < 0.01)
    check("成本：分摊精确到分 分摊合计=共享凭证额",
          rec.get("allocation_balanced") is True
          and abs(float(rec.get("allocation_difference", 9))) < 0.01)
    check("成本：直接法保留正的未归属余额", float(dm.get("unallocated_amount", 0)) > 1000)
    check("成本：分摊法未归属=0 且账面完整",
          abs(float(am.get("unallocated_amount", -1))) < 0.01 and am.get("books_complete") is True)
    check("成本：两条归属路线的未归属余额不同",
          abs(float(dm.get("unallocated_amount", 0)) - float(am.get("unallocated_amount", 0))) > 1)

    check("成本：两方法净总额一致且=凭证总额-已确认抵扣",
          abs(float(dm["net_operating_cost_total"]) - float(am["net_operating_cost_total"])) < 0.01
          and abs(float(dm["net_operating_cost_total"])
                  - (float(rec["voucher_total"]) - float(off["confirmed_offset_total"]))) < 0.01)

    dr = dm["net_cost_per_qualified_unit"]; ar = am["net_cost_per_qualified_unit"]
    check("成本：直接法每合格单元净成本区间有序且有宽度",
          float(dr["low"]) - 1e-6 <= float(dr["point"]) <= float(dr["high"]) + 1e-6
          and float(dr["high"]) - float(dr["low"]) > 1)
    check("成本：分摊法总体区间完整、ITT 敏感性区间包住点估计",
          float(ar["low"]) - 1e-6 <= float(ar["point"]) <= float(ar["high"]) + 1e-6
          and float(am["itt_low"]) - 1e-6 <= float(am["itt_cost_difference_per_unit"])
          <= float(am["itt_high"]) + 1e-6)
    check("成本：按随机臂 ITT 成本差为正（处理臂加班驱动）",
          float(dm["itt_cost_difference_per_unit"]) > 1
          and float(am["itt_cost_difference_per_unit"]) > 1,
          f"direct={dm['itt_cost_difference_per_unit']} alloc={am['itt_cost_difference_per_unit']}")
    check("成本：给出 canonical+3 驱动共 4 个分摊情景",
          len(costs.get("driver_scenarios", []) or []) == 4)

    rb = costs.get("route_balances", []) or []
    sum_exp = sum(float(r.get("unallocated_touched_exposure", 0)) for r in rb)
    check("成本：线路敞口标注不可加且跨线重复列示（敞口和>未归属）",
          rb and all(r.get("exposure_is_additive") is False for r in rb)
          and sum_exp > float(dm["unallocated_amount"]) + 1,
          f"sumExposure={sum_exp} unallocated={dm['unallocated_amount']}")
    check("成本：R3 不经手 R1/R2 跨线共享凭证（其敞口为 0）",
          any(r.get("route_code") == "R3"
              and float(r.get("unallocated_touched_exposure", -1)) == 0 for r in rb))

    check("成本：68000 未确认增收/节省估计被排除在净成本外",
          float(off.get("excluded_unconfirmed_estimate_total", 0)) >= 68000.0
          and int(off.get("unconfirmed_count", 0)) >= 1)

st, uca = req("GET", f"/api/experiments/{eid}/costs/unit-costs?method=DRIVER_ALLOC")
check("成本：结果层 DRIVER_ALLOC 每单元分项 720 行",
      st == 200 and isinstance(uca, dict) and uca.get("total") == 720, str(uca)[:160])
st, ucd = req("GET", f"/api/experiments/{eid}/costs/unit-costs?method=DIRECT")
check("成本：结果层 DIRECT 每单元分项 720 行",
      st == 200 and isinstance(ucd, dict) and ucd.get("total") == 720)

# 抵扣纪律的可重复生命周期：固定 code，先归为未确认以稳定基线
req("POST", f"/api/experiments/{eid}/costs/offsets", user="analyst",
    body={"offsetCode": "OFF-VERIFY-COST", "amount": 1, "confirmed": False})
st, base_cost = req("GET", f"/api/experiments/{eid}/costs")
n0 = base_cost["direct_attribution"]["net_operating_cost_total"]
st, plug = req("POST", f"/api/experiments/{eid}/costs/offsets", user="analyst",
               body={"offsetCode": "OFF-VERIFY-COST", "amount": 888888, "confirmed": False})
check("成本：未确认巨额节省不改变净成本（不得填平）",
      st == 200 and abs(float(plug["net_operating_cost_total"]) - float(n0)) < 0.01,
      f"{plug.get('net_operating_cost_total') if isinstance(plug,dict) else plug} vs {n0}")
st, _ = req("POST", f"/api/experiments/{eid}/costs/offsets", user="analyst",
            body={"offsetCode": "OFF-VERIFY-NOREF", "amount": 500, "confirmed": True})
check("成本：声称已确认但缺 confirmation_ref 被服务端拒绝(400)", st == 400, f"status={st}")
st, conf = req("POST", f"/api/experiments/{eid}/costs/offsets", user="analyst",
               body={"offsetCode": "OFF-VERIFY-COST", "amount": 1337,
                     "confirmed": True, "confirmationRef": "VERIFY-REF"})
check("成本：已确认凭证抵扣精确减少净成本 1337",
      st == 200 and abs(float(conf["net_operating_cost_total"]) - (float(n0) - 1337.0)) < 0.01,
      f"{conf.get('net_operating_cost_total') if isinstance(conf,dict) else conf} vs {float(n0)-1337}")
req("POST", f"/api/experiments/{eid}/costs/offsets", user="analyst",
    body={"offsetCode": "OFF-VERIFY-COST", "amount": 1337, "confirmed": False})
st, after_cost = req("GET", f"/api/experiments/{eid}/costs")
check("成本：还原为未确认后净成本回到基线",
      abs(float(after_cost["direct_attribution"]["net_operating_cost_total"]) - float(n0)) < 0.01)

# 11. OpenAPI / Swagger / 前端
for path in ["/v3/api-docs", "/swagger-ui/index.html", "/index.html", "/login.html", "/app.js"]:
    try:
        with urllib.request.urlopen(BASE + path, timeout=10) as r:
            ok = r.status == 200
    except Exception as e:
        ok = False
    check(f"可访问 {path}", ok)

print(f"\n通过 {len(passes)} 项，失败 {len(fails)} 项")
if fails:
    print("失败项：" + "; ".join(fails))
    sys.exit(1)
print("ALL_VERIFY_CHECKS_PASSED")
PY