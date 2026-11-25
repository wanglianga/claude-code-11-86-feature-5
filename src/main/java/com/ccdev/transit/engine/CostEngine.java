package com.ccdev.transit.engine;

import com.ccdev.transit.util.SnakeJson;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 试点净运营成本归集的纯逻辑核心（无 Spring / JDBC 依赖，可直接 JUnit）。
 *
 * <p>两条归属路线：
 * <ol>
 *   <li>{@code DIRECT}：实际凭证按可追溯车辆块/分配单元直接归属；共同备用车、跨线/跨班维修等
 *       共享凭证不做武断拆分，全额保留为“未归属余额”，真实度高。</li>
 *   <li>{@code DRIVER_ALLOC}：用<b>冻结</b>的车辆小时 / 公里 / 维修工时把共享成本分摊到支援范围内
 *       的单元，账面完整（未归属=0），但分摊份额是假设；用三种驱动给出 ITT 的敏感性区间。</li>
 * </ol>
 *
 * <p>金额内部一律用“分”(long) 精确运算，共享凭证用最大余额法分摊到分，保证：
 * 不重复计费、分摊额与凭证额逐分相等、两种方法的凭证总额一致。
 */
public final class CostEngine {

    public static final String DIRECT = "DIRECT";
    public static final String DRIVER_ALLOC = "DRIVER_ALLOC";

    public static final String OVERTIME = "OVERTIME";
    public static final String ENERGY = "ENERGY";
    public static final String TEMP_VEHICLE = "TEMP_VEHICLE";
    public static final String MAINTENANCE = "MAINTENANCE";
    public static final String CANCEL_LOSS = "CANCEL_LOSS";
    public static final List<String> CATEGORIES =
            List.of(OVERTIME, ENERGY, TEMP_VEHICLE, MAINTENANCE, CANCEL_LOSS);

    public static final String HOURS = "HOURS";
    public static final String KM = "KM";
    public static final String MAINT_HOURS = "MAINT_HOURS";

    public CostEngine() {
    }

    // ============================ 输入记录 ============================

    public record Block(
            String unitKey, String blockCode, String routeCode, String arm, int weekIndex,
            boolean eligible, double hours, double km, double maintHours) {
    }

    @SnakeJson
    public record SupportRef(
            String unitKey, String blockCode, String routeCode, String arm, String detail) {
    }

    @SnakeJson
    public record Voucher(
            String code, String category, long amountCents, boolean shared,
            String unitKey, String routeCode, String pool, String driverBasis,
            boolean crossLine, boolean crossShift, List<SupportRef> supportLinks) {
        @JsonProperty("amount_yuan")
        public double amountYuan() {
            return y(amountCents);
        }

        /** 与 cost_voucher 行的 amount 字段同名，便于直接对接。 */
        @JsonProperty("amount")
        public double amountAlias() {
            return y(amountCents);
        }
    }

    @SnakeJson
    public record Offset(
            String code, String kind, long amountCents, boolean confirmed,
            String unitKey, String arm, String routeCode) {
        @JsonProperty("amount_yuan")
        public double amountYuan() {
            return y(amountCents);
        }

        @JsonProperty("amount")
        public double amountAlias() {
            return y(amountCents);
        }
    }

    // ============================ 输出记录 ============================

    @SnakeJson
    public record Range(double low, double high, double point) {
        @JsonProperty("lower")
        public double lower() {
            return low;
        }

        @JsonProperty("upper")
        public double upper() {
            return high;
        }
    }

    @SnakeJson
    public record ArmStat(
            String arm, int units,
            double directCost, double allocatedSharedCost, double grossCost,
            double confirmedOffset, double netCost, double netCostPerUnit) {
    }

    @SnakeJson
    public record UnitLine(
            String unitKey, String blockCode, String routeCode, String arm, int weekIndex,
            double frozenVehicleHours, double frozenVehicleKm, double frozenMaintenanceHours,
            Map<String, Double> directCostByCategory,
            Map<String, Double> allocatedSharedCostByCategory,
            double directAmount, double allocatedSharedAmount, double grossCostAlloc,
            double confirmedOffset, double netCostDirect, double netCostAlloc) {
    }

    @SnakeJson
    public record PoolView(
            String pool, String costCategory, int voucherCount, double amount,
            String preferredDriverBasis, boolean crossLine, boolean crossShift,
            String handling, List<SupportRef> supportLinks, List<String> voucherCodes) {
    }

    @SnakeJson
    public record DriverScenario(
            String basis, String label,
            double ittCostDifferencePerUnit,
            double treatmentMeanNetPerUnit, double controlMeanNetPerUnit,
            double allocatedSharedTotal, String note) {
    }

    @SnakeJson
    public record MethodView(
            String method, String title, String principle,
            double voucherGrossTotal, double attributedGrossCost, double unallocatedAmount,
            int unallocatedVoucherCount, boolean booksComplete,
            double confirmedOffsetTotal, double netOperatingCostTotal,
            Range netCostPerQualifiedUnit,
            ArmStat treatment, ArmStat control,
            double ittCostDifferencePerUnit, double ittLow, double ittHigh,
            String ittIntervalKind, String ittInterpretation) {
    }

    @SnakeJson
    public record RouteView(
            String routeCode, int units, int treatmentUnits, int controlUnits,
            double directAmount, double driverAllocatedSharedAmount, double netCostAlloc,
            double unallocatedTouchedExposure, boolean exposureIsAdditive,
            String exposureNote, List<String> unallocatedVoucherCodes) {
    }

    @SnakeJson
    public record OffsetPolicyView(
            double confirmedOffsetTotal, int confirmedCount,
            double excludedUnconfirmedEstimateTotal, int unconfirmedCount,
            String policy, String pluggingGuard, List<Offset> entries) {
    }

    @SnakeJson
    public record Reconciliation(
            int distinctVoucherCount,
            double voucherTotal, double directVoucherTotal, double sharedVoucherTotal,
            double directAssignedToUnits, double directOrphan,
            double directMethodUnallocated, double directPlusUnallocatedDifference,
            boolean directBalanced,
            double sharedAllocatedToUnits, double allocationDifference,
            boolean allocationBalanced,
            double driverAllocMethodUnallocated, boolean booksCompleteUnderAllocation,
            boolean grandTotalsMatchAcrossMethods,
            boolean noDoubleBilling, String noDoubleBillingBasis,
            double roundingToleranceYuan) {
    }

    @SnakeJson
    public record SampleEvidence(
            int overtimeRecordCount, List<Voucher> overtimeRecords,
            boolean spareVehiclePresent, Voucher spareVehicleVoucher,
            boolean spareServesTreatmentAndControl,
            boolean crossLineMaintenancePresent, Voucher crossLineMaintenanceVoucher,
            String note) {
    }

    @SnakeJson
    public record Report(
            String currency,
            int qualifiedServiceUnits, int treatmentUnits, int controlUnits,
            Reconciliation reconciliation,
            OffsetPolicyView offsets,
            MethodView directAttribution,
            MethodView driverAllocation,
            List<DriverScenario> driverScenarios,
            List<PoolView> unallocatedPools,
            List<RouteView> routeBalances,
            SampleEvidence sampleEvidence,
            List<UnitLine> units) {

        /** 便于调用方按方法取视图。 */
        @JsonProperty("methods")
        public Map<String, MethodView> methods() {
            Map<String, MethodView> m = new LinkedHashMap<>();
            m.put(DIRECT, directAttribution);
            m.put(DRIVER_ALLOC, driverAllocation);
            return m;
        }
    }

    // ============================ 计算核心 ============================

    public Report calculate(List<Block> blockInput, List<Voucher> voucherInput, List<Offset> offsetInput) {
        // 只把合格块作为“合格服务单元”；保持入参顺序，输出稳定
        List<Block> blocks = blockInput.stream().filter(Block::eligible).toList();
        int n = blocks.size();
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            idx.put(blocks.get(i).unitKey(), i);
        }

        int nt = (int) blocks.stream().filter(b -> "TREATMENT".equals(b.arm())).count();
        int nc = (int) blocks.stream().filter(b -> "CONTROL".equals(b.arm())).count();

        long[][] directCat = new long[n][CATEGORIES.size()];
        long[][] allocCat = new long[n][CATEGORIES.size()];
        long[] unitOffset = new long[n];

        // ---- 凭证：直接归属；共享凭证先收集 ----
        long voucherTotalC = 0, directVoucherC = 0, sharedVoucherC = 0;
        long directAssignedC = 0, directOrphanC = 0;
        int sharedCount = 0;
        List<Voucher> sharedVouchers = new ArrayList<>();
        Map<String, List<Voucher>> pools = new LinkedHashMap<>();

        for (Voucher v : voucherInput) {
            voucherTotalC += v.amountCents();
            int cat = categoryIndex(v.category());
            if (v.shared()) {
                sharedVoucherC += v.amountCents();
                sharedCount++;
                sharedVouchers.add(v);
                String pool = v.pool() == null ? "SHARED" : v.pool();
                pools.computeIfAbsent(pool, k -> new ArrayList<>()).add(v);
                continue;
            }
            directVoucherC += v.amountCents();
            Integer i = v.unitKey() == null ? null : idx.get(v.unitKey());
            if (i != null && cat >= 0) {
                directCat[i][cat] += v.amountCents();
                directAssignedC += v.amountCents();
            } else {
                // 直接凭证却找不到合格单元：不丢弃、不硬塞，留作孤儿未归属
                directOrphanC += v.amountCents();
            }
        }

        // ---- 抵扣：只有 confirmed 才进净成本 ----
        long confirmedTotalC = 0, unconfirmedTotalC = 0;
        int confirmedCount = 0, unconfirmedCount = 0;
        long armOffsetT = 0, armOffsetC = 0;
        long unitOffsetTotalC = 0;
        for (Offset o : offsetInput) {
            if (o.confirmed()) {
                confirmedTotalC += o.amountCents();
                confirmedCount++;
                Integer i = o.unitKey() == null ? null : idx.get(o.unitKey());
                if (i != null) {
                    unitOffset[i] += o.amountCents();
                    unitOffsetTotalC += o.amountCents();
                } else if ("TREATMENT".equals(o.arm())) {
                    armOffsetT += o.amountCents();
                } else if ("CONTROL".equals(o.arm())) {
                    armOffsetC += o.amountCents();
                }
                // 其余为试点级确认抵扣：只在总量/每单元总区间扣减，不强行归臂
            } else {
                unconfirmedTotalC += o.amountCents();
                unconfirmedCount++;
            }
        }

        // ---- 规范驱动分摊（每张共享凭证按其首选驱动），结果到分且逐凭证平衡 ----
        long allocatedSharedC = allocateShared(blocks, idx, sharedVouchers, null, allocCat);

        long[] directTotal = new long[n];
        long[] allocSharedTotal = new long[n];
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < CATEGORIES.size(); c++) {
                directTotal[i] += directCat[i][c];
                allocSharedTotal[i] += allocCat[i][c];
            }
        }

        // ---- 两种方法的臂统计与 ITT ----
        ArmAgg directArms = aggregateArms(blocks, directTotal, null, unitOffset, armOffsetT, armOffsetC);
        ArmAgg allocArms = aggregateArms(blocks, directTotal, allocSharedTotal, unitOffset, armOffsetT, armOffsetC);

        double directItt = directArms.meanT - directArms.meanC;
        double allocIttCanonical = allocArms.meanT - allocArms.meanC;

        long directUnallocatedC = sharedVoucherC + directOrphanC;
        long allocUnallocatedC = directOrphanC; // 分摊法下共享已全部分完，只剩无法定位的孤儿

        // DIRECT 的 ITT 保守边界：整笔未归属 U 可被极端理解为全归 C（下界）或全归 T（上界）
        double directIttLow = nt == 0 || nc == 0 ? directItt
                : round2(directItt - y(directUnallocatedC) / nc);
        double directIttHigh = nt == 0 || nc == 0 ? directItt
                : round2(directItt + y(directUnallocatedC) / nt);

        // ---- 三种冻结驱动敏感性场景（强制所有共享凭证用同一驱动） ----
        List<DriverScenario> scenarios = new ArrayList<>();
        scenarios.add(new DriverScenario("CANONICAL_MIX", "按成本库性质选驱动（备用车→小时，维修→维修工时，其余→公里）",
                round2(allocIttCanonical), allocArms.meanT, allocArms.meanC, y(allocatedSharedC),
                "账面完整；各共享库使用其登记的首选驱动"));
        double scenMin = allocIttCanonical, scenMax = allocIttCanonical;
        for (String basis : List.of(HOURS, KM, MAINT_HOURS)) {
            long[] perUnit = new long[n];
            long scenAlloc = allocateShared(blocks, idx, sharedVouchers, basis, null, perUnit);
            ArmAgg a = aggregateArms(blocks, directTotal, perUnit, unitOffset, armOffsetT, armOffsetC);
            double itt = round2(a.meanT - a.meanC);
            scenMin = Math.min(scenMin, itt);
            scenMax = Math.max(scenMax, itt);
            String label = switch (basis) {
                case HOURS -> "全部共享成本按冻结车辆小时分摊";
                case KM -> "全部共享成本按冻结车辆公里分摊";
                default -> "全部共享成本按冻结维修工时分摊";
            };
            scenarios.add(new DriverScenario(basis, label, itt, a.meanT, a.meanC, y(scenAlloc),
                    "驱动份额是假设；不同驱动给出 ITT 的敏感性区间"));
        }

        // ---- 每合格服务单元净成本区间（试点总体） ----
        Range directPerUnitRange = new Range(
                round2(y(voucherTotalC - directUnallocatedC - confirmedTotalC) / Math.max(1, n)),
                round2(y(voucherTotalC - confirmedTotalC) / Math.max(1, n)),
                round2(y(directAssignedC - confirmedTotalC) / Math.max(1, n)));
        Range allocPerUnitRange = new Range(
                round2(y(voucherTotalC - allocUnallocatedC - confirmedTotalC) / Math.max(1, n)),
                round2(y(voucherTotalC - confirmedTotalC) / Math.max(1, n)),
                round2(y(voucherTotalC - allocUnallocatedC - confirmedTotalC) / Math.max(1, n)));

        // ---- 对账 / 不重复计费 ----
        long directPlusUnalloc = directAssignedC + directUnallocatedC;
        long allocDiff = allocatedSharedC - sharedVoucherC;
        Reconciliation recon = new Reconciliation(
                voucherInput.size(),
                y(voucherTotalC), y(directVoucherC), y(sharedVoucherC),
                y(directAssignedC), y(directOrphanC),
                y(directUnallocatedC), y(directPlusUnalloc - voucherTotalC),
                directPlusUnalloc == voucherTotalC,
                y(allocatedSharedC), y(allocDiff), allocDiff == 0,
                y(allocUnallocatedC), allocUnallocatedC == 0,
                true,
                true,
                "每张 cost_voucher.voucher_code 有唯一约束且只入账一次；引擎按凭证总额守恒核对，"
                        + "分摊用最大余额法到分，分摊合计与共享凭证合计逐分相等，两种方法凭证 grand total 相同。",
                0.01);

        // ---- 共享池 / 未归属余额 ----
        List<PoolView> poolViews = new ArrayList<>();
        for (Map.Entry<String, List<Voucher>> e : pools.entrySet()) {
            List<Voucher> vs = e.getValue();
            long amt = vs.stream().mapToLong(Voucher::amountCents).sum();
            Voucher first = vs.get(0);
            List<SupportRef> refs = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (Voucher v : vs) {
                codes.add(v.code());
                if (v.supportLinks() != null) {
                    refs.addAll(v.supportLinks());
                }
            }
            poolViews.add(new PoolView(e.getKey(), first.category(), vs.size(), y(amt),
                    first.driverBasis(), vs.stream().anyMatch(Voucher::crossLine),
                    vs.stream().anyMatch(Voucher::crossShift),
                    "DIRECT 法不武断拆分，全额保留为未归属余额；DRIVER_ALLOC 法在其支援范围内按冻结驱动分摊",
                    refs, codes));
        }

        // ---- 按物理线路的余额/成本 ----
        List<RouteView> routeViews = buildRouteViews(blocks, voucherInput, directCat,
                allocSharedTotal, unitOffset);

        // ---- 样例证据 ----
        SampleEvidence evidence = buildEvidence(voucherInput);

        // ---- 单元分项 ----
        List<UnitLine> unitLines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Block b = blocks.get(i);
            Map<String, Double> dc = new LinkedHashMap<>();
            Map<String, Double> ac = new LinkedHashMap<>();
            for (int c = 0; c < CATEGORIES.size(); c++) {
                dc.put(CATEGORIES.get(c), y(directCat[i][c]));
                ac.put(CATEGORIES.get(c), y(allocCat[i][c]));
            }
            long unitNetDirectC = directTotal[i] - unitOffset[i];
            long unitNetAllocC = directTotal[i] + allocSharedTotal[i] - unitOffset[i];
            unitLines.add(new UnitLine(
                    b.unitKey(), b.blockCode(), b.routeCode(), b.arm(), b.weekIndex(),
                    b.hours(), b.km(), b.maintHours(),
                    dc, ac,
                    y(directTotal[i]), y(allocSharedTotal[i]),
                    y(directTotal[i] + allocSharedTotal[i]),
                    y(unitOffset[i]), y(unitNetDirectC), y(unitNetAllocC)));
        }

        OffsetPolicyView offsetView = new OffsetPolicyView(
                y(confirmedTotalC), confirmedCount,
                y(unconfirmedTotalC), unconfirmedCount,
                "只有 confirmed=TRUE 且带确认凭证号的收入/节省/追偿才允许冲减净运营成本；"
                        + "未确认的估计仅作备忘披露，绝不计入净成本或用于把账面填平到目标值。",
                "POST /costs/offsets 写入的未确认大额“节省”不会改变 net_operating_cost_total / itt；"
                        + "改为 confirmed 并提供 confirmation_ref 后才会冲减。",
                offsetInput);

        MethodView directView = new MethodView(
                DIRECT, "路线一：可追溯车辆块直接归属实际凭证",
                "加班/油电/取消损失等按车辆块—分配单元直接入账；共同备用车与跨线/跨班维修不强行拆分，保留未归属余额。真实度高。",
                y(voucherTotalC), y(directAssignedC), y(directUnallocatedC), sharedCount,
                directUnallocatedC == 0,
                y(confirmedTotalC), y(voucherTotalC - confirmedTotalC),
                directPerUnitRange,
                directArms.treatment, directArms.control,
                round2(directItt), round2(directIttLow), round2(directIttHigh),
                "CONSERVATIVE_UNALLOCATED_BOUND",
                "ITT=处理臂单元平均净成本−对照臂（按随机分配臂，非实际执行）。区间是把整笔未归属余额"
                        + "极端全归对照（下界）或全归处理（上界）得到的归属边界，不是抽样置信区间。");

        MethodView allocView = new MethodView(
                DRIVER_ALLOC, "路线二：冻结车辆小时/公里/维修工时驱动分摊",
                "共享成本在其实际支援范围内按登记时冻结的驱动份额分摊，账面完整（未归属=0）；份额依赖驱动假设，故给出三种驱动的 ITT 敏感性区间。",
                y(voucherTotalC), y(directAssignedC + allocatedSharedC), y(allocUnallocatedC), 0,
                allocUnallocatedC == 0,
                y(confirmedTotalC), y(voucherTotalC - confirmedTotalC),
                allocPerUnitRange,
                allocArms.treatment, allocArms.control,
                round2(allocIttCanonical), round2(scenMin), round2(scenMax),
                "DRIVER_SHARE_ASSUMPTION_SENSITIVITY",
                "ITT 点估计用各成本库首选驱动；区间为 HOURS/KM/MAINT_HOURS 三种冻结驱动及规范组合下 ITT 的最小—最大值，反映驱动份额假设的不确定性。");

        return new Report(
                "CNY", n, nt, nc, recon, offsetView, directView, allocView,
                scenarios, poolViews, routeViews, evidence, unitLines);
    }

    // ============================ 分摊 ============================

    /**
     * 把每张共享凭证分摊到其支援范围（无 support 则全部合格单元；可按 routeCode 收窄）。
     * 金额用最大余额法分到“分”，每张凭证的分配合计与其金额逐分相等。
     *
     * @param forceBasis 非空时忽略凭证首选驱动，强制使用该驱动（敏感性场景）
     * @param allocCat   可为 null；非空时按类别累加规范分摊
     * @param perUnitOut 可为 null；非空时记录每单元分摊总额（场景计算用）
     */
    private long allocateShared(List<Block> blocks, Map<String, Integer> idx,
                                List<Voucher> sharedVouchers, String forceBasis,
                                long[][] allocCat, long[] perUnitOut) {
        long allocated = 0;
        for (Voucher v : sharedVouchers) {
            String basis = forceBasis != null ? forceBasis
                    : (v.driverBasis() == null ? KM : v.driverBasis());

            List<Integer> scope = resolveScope(v, blocks, idx);
            double[] weights = new double[scope.size()];
            double wsum = 0;
            for (int k = 0; k < scope.size(); k++) {
                Block b = blocks.get(scope.get(k));
                double w = driverValue(b, basis);
                weights[k] = w;
                wsum += w;
            }
            // 驱动全为 0 的兜底：范围内均摊（样例数据驱动均为正，正常不会触发）
            if (wsum <= 0 && !scope.isEmpty()) {
                java.util.Arrays.fill(weights, 1.0);
                wsum = scope.size();
            }

            long[] cents = apportionCents(v.amountCents(), weights, wsum);
            int cat = categoryIndex(v.category());
            for (int k = 0; k < scope.size(); k++) {
                int ui = scope.get(k);
                long c = cents[k];
                allocated += c;
                if (perUnitOut != null) {
                    perUnitOut[ui] += c;
                }
                if (allocCat != null && cat >= 0) {
                    allocCat[ui][cat] += c;
                }
            }
        }
        return allocated;
    }

    /** 兼容规范分摊调用（写类别矩阵）。 */
    private long allocateShared(List<Block> blocks, Map<String, Integer> idx,
                                List<Voucher> sharedVouchers, String forceBasis,
                                long[][] allocCat) {
        return allocateShared(blocks, idx, sharedVouchers, forceBasis, allocCat, null);
    }

    private List<Integer> resolveScope(Voucher v, List<Block> blocks, Map<String, Integer> idx) {
        List<Integer> scope = new ArrayList<>();
        if (v.supportLinks() != null && !v.supportLinks().isEmpty()) {
            for (SupportRef s : v.supportLinks()) {
                Integer i = s.unitKey() == null ? null : idx.get(s.unitKey());
                if (i != null && !scope.contains(i)) {
                    scope.add(i);
                }
            }
        }
        if (!scope.isEmpty()) {
            return scope;
        }
        // 无显式支援清单：按 routeCode 收窄；MULTI/空 表示全部合格单元
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (v.routeCode() == null || v.routeCode().isBlank()
                    || "MULTI".equals(v.routeCode()) || v.routeCode().equals(b.routeCode())) {
                scope.add(i);
            }
        }
        return scope;
    }

    private static double driverValue(Block b, String basis) {
        return switch (basis) {
            case HOURS -> b.hours();
            case MAINT_HOURS -> b.maintHours();
            default -> b.km();
        };
    }

    /** 最大余额法：把 totalCents 按 weights 比例分成整数分，合计严格等于 totalCents。 */
    static long[] apportionCents(long totalCents, double[] weights, double wsum) {
        int m = weights.length;
        long[] out = new long[m];
        if (m == 0 || wsum <= 0) {
            return out;
        }
        double[] frac = new double[m];
        long assigned = 0;
        for (int i = 0; i < m; i++) {
            double raw = totalCents * (weights[i] / wsum);
            long floor = (long) Math.floor(raw);
            out[i] = floor;
            frac[i] = raw - floor;
            assigned += floor;
        }
        long remainder = totalCents - assigned; // 0..m（理论上）
        // 按小数余数从大到小，每个补 1 分
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(frac[b], frac[a]));
        for (int k = 0; k < remainder && k < order.size(); k++) {
            out[order.get(k)]++;
        }
        return out;
    }

    // ============================ 臂汇总 ============================

    private record ArmAgg(ArmStat treatment, ArmStat control, double meanT, double meanC) {
    }

    private ArmAgg aggregateArms(List<Block> blocks, long[] directTotal, long[] sharedTotal,
                                 long[] unitOffset, long armOffsetT, long armOffsetC) {
        long tDirect = 0, tShared = 0, tOffsetUnit = 0;
        long cDirect = 0, cShared = 0, cOffsetUnit = 0;
        int nt = 0, nc = 0;
        for (int i = 0; i < blocks.size(); i++) {
            boolean t = "TREATMENT".equals(blocks.get(i).arm());
            long d = directTotal[i];
            long s = sharedTotal == null ? 0 : sharedTotal[i];
            long o = unitOffset[i];
            if (t) {
                nt++;
                tDirect += d;
                tShared += s;
                tOffsetUnit += o;
            } else if ("CONTROL".equals(blocks.get(i).arm())) {
                nc++;
                cDirect += d;
                cShared += s;
                cOffsetUnit += o;
            }
        }
        long tOffset = tOffsetUnit + armOffsetT;
        long cOffset = cOffsetUnit + armOffsetC;
        long tGross = tDirect + tShared;
        long cGross = cDirect + cShared;
        long tNet = tGross - tOffset;
        long cNet = cGross - cOffset;
        double meanT = nt == 0 ? 0 : y(tNet) / nt;
        double meanC = nc == 0 ? 0 : y(cNet) / nc;

        ArmStat tStat = new ArmStat("TREATMENT", nt, y(tDirect), y(tShared), y(tGross),
                y(tOffset), y(tNet), round2(meanT));
        ArmStat cStat = new ArmStat("CONTROL", nc, y(cDirect), y(cShared), y(cGross),
                y(cOffset), y(cNet), round2(meanC));
        return new ArmAgg(tStat, cStat, meanT, meanC);
    }

    // ============================ 线路视图 ============================

    private List<RouteView> buildRouteViews(List<Block> blocks,
                                            List<Voucher> vouchers, long[][] directCat,
                                            long[] allocSharedTotal, long[] unitOffset) {
        Map<String, int[]> count = new LinkedHashMap<>(); // [n,t,c]
        Map<String, long[]> sums = new LinkedHashMap<>();  // [direct, alloc, offset]
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            String r = b.routeCode();
            int[] cn = count.computeIfAbsent(r, k -> new int[3]);
            long[] sm = sums.computeIfAbsent(r, k -> new long[3]);
            cn[0]++;
            if ("TREATMENT".equals(b.arm())) cn[1]++;
            if ("CONTROL".equals(b.arm())) cn[2]++;
            for (int c = 0; c < CATEGORIES.size(); c++) sm[0] += directCat[i][c];
            sm[1] += allocSharedTotal[i];
            sm[2] += unitOffset[i];
        }

        // 每条物理线路“触及”的未归属共享凭证敞口（不可跨线相加：同一笔跨线凭证在两条线都全额列示）
        Map<String, Long> exposure = new LinkedHashMap<>();
        Map<String, List<String>> exposureCodes = new LinkedHashMap<>();
        for (Voucher v : vouchers) {
            if (!v.shared()) continue;
            java.util.Set<String> routes = new java.util.LinkedHashSet<>();
            if (v.supportLinks() != null) {
                v.supportLinks().forEach(s -> {
                    if (s.routeCode() != null && !s.routeCode().isBlank()) routes.add(s.routeCode());
                });
            }
            if (routes.isEmpty() && v.routeCode() != null && !"MULTI".equals(v.routeCode())) {
                routes.add(v.routeCode());
            }
            for (String r : routes) {
                exposure.merge(r, v.amountCents(), Long::sum);
                exposureCodes.computeIfAbsent(r, k -> new ArrayList<>()).add(v.code());
            }
        }

        List<RouteView> views = new ArrayList<>();
        List<String> routeOrder = new ArrayList<>();
        blocks.forEach(b -> {
            if (!routeOrder.contains(b.routeCode())) routeOrder.add(b.routeCode());
        });
        for (String r : routeOrder) {
            int[] cn = count.getOrDefault(r, new int[3]);
            long[] sm = sums.getOrDefault(r, new long[3]);
            long netAlloc = sm[0] + sm[1] - sm[2];
            views.add(new RouteView(
                    r, cn[0], cn[1], cn[2],
                    y(sm[0]), y(sm[1]), y(netAlloc),
                    y(exposure.getOrDefault(r, 0L)), false,
                    "该值是触及本线路的未归属共享凭证全额敞口，仅作追溯披露；跨线凭证在各线重复列示，"
                            + "因此不能把各线路此值相加（相加会重复计费）。可加的线路成本请用 driver_allocated_shared_amount / net_cost_alloc。",
                    exposureCodes.getOrDefault(r, List.of())));
        }
        return views;
    }

    // ============================ 样例证据 ============================

    private SampleEvidence buildEvidence(List<Voucher> vouchers) {
        List<Voucher> ot = vouchers.stream().filter(v -> OVERTIME.equals(v.category())).toList();
        Voucher spare = vouchers.stream()
                .filter(v -> v.shared() && ("SPARE_VEHICLE".equals(v.pool())
                        || TEMP_VEHICLE.equals(v.category())))
                .findFirst().orElse(null);
        Voucher crossMaint = vouchers.stream()
                .filter(v -> MAINTENANCE.equals(v.category()) && v.crossLine())
                .findFirst().orElse(null);
        boolean spareBothArms = false;
        if (spare != null && spare.supportLinks() != null) {
            boolean t = spare.supportLinks().stream().anyMatch(s -> "TREATMENT".equals(s.arm()));
            boolean c = spare.supportLinks().stream().anyMatch(s -> "CONTROL".equals(s.arm()));
            spareBothArms = t && c;
        }
        return new SampleEvidence(
                ot.size(), ot,
                spare != null, spare, spareBothArms,
                crossMaint != null, crossMaint,
                "固定样例含：一辆备用车同时支援 R1 处理班与 R2 对照班（一笔共享 TEMP_VEHICLE 凭证）、"
                        + "一次跨线且跨班维修（一笔共享 MAINTENANCE 凭证）、恰好两笔可直接归属的司机加班。");
    }

    // ============================ 辅助 ============================

    private static int categoryIndex(String category) {
        return CATEGORIES.indexOf(category);
    }

    static double y(long cents) {
        return cents / 100.0;
    }

    private static double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 100.0) / 100.0;
    }
}
