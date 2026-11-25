package com.ccdev.transit.engine;

import com.ccdev.transit.engine.CostEngine.Block;
import com.ccdev.transit.engine.CostEngine.Offset;
import com.ccdev.transit.engine.CostEngine.Report;
import com.ccdev.transit.engine.CostEngine.SupportRef;
import com.ccdev.transit.engine.CostEngine.Voucher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成本归集纯逻辑不变量：到分分摊、不重复计费、直接法留未归属、驱动法清零、
 * ITT 符号/边界、以及“未确认估计不得冲减成本”。
 */
class CostEngineTest {

    private static final double EPS = 1e-9;

    /** 4 个合格单元：R1/R2 各一 T 一 C，驱动完全相等，便于手算。 */
    private List<Block> blocks() {
        return List.of(
                new Block("U1", "B1", "R1", "TREATMENT", 1, true, 1, 10, 1),
                new Block("U2", "B2", "R1", "CONTROL", 1, true, 1, 10, 1),
                new Block("U3", "B3", "R2", "TREATMENT", 1, true, 1, 10, 1),
                new Block("U4", "B4", "R2", "CONTROL", 1, true, 1, 10, 1));
    }

    private List<SupportRef> spareRefs() {
        return List.of(
                new SupportRef("U1", "B1", "R1", "TREATMENT", "支援 R1 处理班"),
                new SupportRef("U2", "B2", "R1", "CONTROL", "支援 R2 对照班"));
    }

    private List<SupportRef> maintRefs() {
        return List.of(
                new SupportRef("U1", "B1", "R1", "TREATMENT", ""),
                new SupportRef("U2", "B2", "R1", "CONTROL", ""),
                new SupportRef("U3", "B3", "R2", "TREATMENT", ""),
                new SupportRef("U4", "B4", "R2", "CONTROL", ""));
    }

    private List<Voucher> vouchers() {
        List<Voucher> vs = new ArrayList<>();
        // 两笔加班，都在处理臂：100 + 60 元
        vs.add(new Voucher("OT1", CostEngine.OVERTIME, 10_000L, false,
                "U1", "R1", null, null, false, false, null));
        vs.add(new Voucher("OT2", CostEngine.OVERTIME, 6_000L, false,
                "U3", "R2", null, null, false, false, null));
        // 共享备用车 400 元，支援 U1(T)+U2(C)，相等小时 → 各 200
        vs.add(new Voucher("SP1", CostEngine.TEMP_VEHICLE, 40_000L, true,
                null, "MULTI", "SPARE_VEHICLE", CostEngine.HOURS, true, false, spareRefs()));
        // 跨线维修 200 元，4 单元相等维修工时 → 各 50
        vs.add(new Voucher("MX1", CostEngine.MAINTENANCE, 20_000L, true,
                null, "MULTI", "CROSS_LINE_MAINT", CostEngine.MAINT_HOURS, true, true, maintRefs()));
        return vs;
    }

    private List<Offset> offsets() {
        return List.of(
                new Offset("O1", "CLAIM", 1_000L, true, "U1", null, null),       // 已确认 10 元
                new Offset("O9", "REVENUE", 9_999_999L, false, null, null, null)); // 未确认巨额估计
    }

    @Test
    void apportionIsExactToTheCent() {
        long[] three = CostEngine.apportionCents(100, new double[]{1, 1, 1}, 3);
        assertEquals(100, three[0] + three[1] + three[2]);
        for (long c : three) {
            assertTrue(c == 33 || c == 34);
        }
        long[] one = CostEngine.apportionCents(1, new double[]{1, 1, 1}, 3);
        assertEquals(1, one[0] + one[1] + one[2]);
        long[] zero = CostEngine.apportionCents(0, new double[]{1, 1}, 2);
        assertEquals(0, zero[0] + zero[1]);
    }

    @Test
    void directKeepsSharedUnallocatedAndAllocationClearsItWithoutDoubleBilling() {
        Report r = new CostEngine().calculate(blocks(), vouchers(), offsets());

        assertEquals(4, r.qualifiedServiceUnits());
        assertEquals(2, r.treatmentUnits());
        assertEquals(2, r.controlUnits());

        // 凭证总额 760 = 直接 160 + 共享 600
        var rec = r.reconciliation();
        assertEquals(760.0, rec.voucherTotal(), EPS);
        assertEquals(160.0, rec.directVoucherTotal(), EPS);
        assertEquals(600.0, rec.sharedVoucherTotal(), EPS);
        assertTrue(rec.noDoubleBilling());

        // DIRECT：直接归属 160，未归属 600，160+600=760 平衡
        var direct = r.directAttribution();
        assertEquals(160.0, direct.attributedGrossCost(), EPS);
        assertEquals(600.0, direct.unallocatedAmount(), EPS);
        assertFalse(direct.booksComplete());
        assertTrue(rec.directBalanced());
        assertEquals(0.0, rec.directPlusUnallocatedDifference(), EPS);

        // DRIVER_ALLOC：共享 600 全部分完，未归属 0，分摊合计=共享凭证额
        var alloc = r.driverAllocation();
        assertEquals(760.0, alloc.attributedGrossCost(), EPS);
        assertEquals(0.0, alloc.unallocatedAmount(), EPS);
        assertTrue(alloc.booksComplete());
        assertTrue(rec.allocationBalanced());
        assertEquals(0.0, rec.allocationDifference(), EPS);
        assertEquals(600.0, rec.sharedAllocatedToUnits(), EPS);
        assertTrue(rec.grandTotalsMatchAcrossMethods());

        // 两种方法的凭证 gross total 相同，且净成本只扣已确认 10 元 → 750
        assertEquals(760.0, direct.voucherGrossTotal(), EPS);
        assertEquals(760.0, alloc.voucherGrossTotal(), EPS);
        assertEquals(750.0, direct.netOperatingCostTotal(), EPS);
        assertEquals(750.0, alloc.netOperatingCostTotal(), EPS);
    }

    @Test
    void ittFollowsRandomArmAndDirectUnallocatedBoundsBracketPoint() {
        Report r = new CostEngine().calculate(blocks(), vouchers(), offsets());

        // DIRECT：T 直接成本 160，U1 已确认抵扣 10 → T 净 150 /2 =75；C=0
        var d = r.directAttribution();
        assertEquals(75.0, d.treatment().netCostPerUnit(), EPS);
        assertEquals(0.0, d.control().netCostPerUnit(), EPS);
        assertEquals(75.0, d.ittCostDifferencePerUnit(), EPS);
        // U=600, N_T=N_C=2：下界 75-300=-225，上界 75+300=375
        assertEquals(-225.0, d.ittLow(), EPS);
        assertEquals(375.0, d.ittHigh(), EPS);
        assertTrue(d.ittLow() <= d.ittCostDifferencePerUnit());
        assertTrue(d.ittCostDifferencePerUnit() <= d.ittHigh());

        // 每合格单元净成本区间：低=(760-600-10)/4=37.5，高=(760-10)/4=187.5
        assertEquals(37.5, d.netCostPerQualifiedUnit().low(), EPS);
        assertEquals(187.5, d.netCostPerQualifiedUnit().high(), EPS);
        assertTrue(d.netCostPerQualifiedUnit().lower() == d.netCostPerQualifiedUnit().low());
        assertTrue(d.netCostPerQualifiedUnit().upper() == d.netCostPerQualifiedUnit().high());

        // ALLOC：相等权重使 T/C 各承担 300 共享，ITT 点估计仍为 75；场景区间包住点
        var a = r.driverAllocation();
        assertEquals(75.0, a.ittCostDifferencePerUnit(), EPS);
        assertTrue(a.ittLow() <= a.ittCostDifferencePerUnit() + EPS);
        assertTrue(a.ittHigh() + EPS >= a.ittCostDifferencePerUnit());
        assertEquals(0.0, a.unallocatedAmount(), EPS);
        // 总体每单元账面完整：750/4 = 187.5，区间退化为同一点
        assertEquals(187.5, a.netCostPerQualifiedUnit().point(), EPS);
        assertEquals(a.netCostPerQualifiedUnit().low(), a.netCostPerQualifiedUnit().high(), EPS);
        // canonical + 3 驱动 = 4 个场景
        assertEquals(4, r.driverScenarios().size());
    }

    @Test
    void unconfirmedEstimateIsDisclosedButNeverNetsCost() {
        Report with = new CostEngine().calculate(blocks(), vouchers(), offsets());
        List<Offset> onlyConfirmed = List.of(offsets().get(0));
        Report without = new CostEngine().calculate(blocks(), vouchers(), onlyConfirmed);

        // 99999.99 元未确认估计被披露为排除项
        assertEquals(99_999.99, with.offsets().excludedUnconfirmedEstimateTotal(), EPS);
        assertEquals(1, with.offsets().unconfirmedCount());
        // 加不加未确认估计，净运营成本完全一致
        assertEquals(without.directAttribution().netOperatingCostTotal(),
                with.directAttribution().netOperatingCostTotal(), EPS);
        assertEquals(750.0, with.directAttribution().netOperatingCostTotal(), EPS);
    }

    @Test
    void orphanDirectVoucherStaysUnallocatedUnderBothMethods() {
        List<Voucher> vs = new ArrayList<>(vouchers());
        vs.add(new Voucher("ORPHAN", CostEngine.MAINTENANCE, 5_000L, false,
                "NO_SUCH_UNIT", "R9", null, null, false, false, null));
        Report r = new CostEngine().calculate(blocks(), vs, List.of());
        // 直接法未归属 = 共享 600 + 孤儿 50
        assertEquals(650.0, r.directAttribution().unallocatedAmount(), EPS);
        // 分摊法只清共享，孤儿 50 仍保留为未归属，不被硬塞进任何单元
        assertEquals(50.0, r.driverAllocation().unallocatedAmount(), EPS);
        assertFalse(r.driverAllocation().booksComplete());
        assertTrue(r.reconciliation().directBalanced());
    }

    @Test
    void sampleEvidenceDetectsSpareAcrossArmsAndCrossLineMaintenance() {
        Report r = new CostEngine().calculate(blocks(), vouchers(), offsets());
        var ev = r.sampleEvidence();
        assertEquals(2, ev.overtimeRecordCount());
        assertTrue(ev.spareVehiclePresent());
        assertTrue(ev.spareServesTreatmentAndControl());
        assertTrue(ev.crossLineMaintenancePresent());
    }
}
