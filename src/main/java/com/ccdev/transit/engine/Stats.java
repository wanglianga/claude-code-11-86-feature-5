package com.ccdev.transit.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 估计与不确定性工具：
 * 加权 Hajek 均值差（ITT）、按随机化单元聚类的 CR1 三明治方差（2 参数 WLS）、
 * Wilson 比率区间、t 临界值与正态 p 值。全部为无外部依赖的可复现计算。
 */
public final class Stats {

    private Stats() {
    }

    public record Observation(double y, double w, int a, String group) {
    }

    public record EffectEstimate(
            double estimate,
            double se,
            double ciLow,
            double ciHigh,
            double pValue,
            int n,
            int groups,
            double df) {
    }

    /** 加权组均值（Hajek）。 */
    public static double weightedMean(List<Double> values, List<Double> weights) {
        double sw = 0, swy = 0;
        for (int i = 0; i < values.size(); i++) {
            sw += weights.get(i);
            swy += values.get(i) * weights.get(i);
        }
        return sw == 0 ? Double.NaN : swy / sw;
    }

    /**
     * 加权 ITT + 聚类稳健 SE。设计矩阵 X=[1,a]，W=diag(w)，
     * meat=Σ_g (X_g' W_g e_g)(...)'，CR1 小样本修正 G/(G-1)。
     */
    public static EffectEstimate clusterITT(List<Observation> obs) {
        int n = obs.size();
        double s00 = 0, s01 = 0, s11 = 0, v0 = 0, v1 = 0;
        for (Observation o : obs) {
            double w = o.w();
            double a = o.a();
            s00 += w;
            s01 += w * a;
            s11 += w * a * a;
            v0 += w * o.y();
            v1 += w * a * o.y();
        }
        double det = s00 * s11 - s01 * s01;
        // (X'WX)^-1
        double b00 = s11 / det;
        double b01 = -s01 / det;
        double b11 = s00 / det;
        double beta0 = b00 * v0 + b01 * v1;
        double beta1 = b01 * v0 + b11 * v1;

        Map<String, double[]> scores = new LinkedHashMap<>();
        for (Observation o : obs) {
            double pred = beta0 + beta1 * o.a();
            double e = o.y() - pred;
            double[] sg = scores.computeIfAbsent(o.group(), k -> new double[2]);
            sg[0] += o.w() * e;
            sg[1] += o.w() * o.a() * e;
        }
        int g = scores.size();

        double m00 = 0, m01 = 0, m11 = 0;
        for (double[] s : scores.values()) {
            m00 += s[0] * s[0];
            m01 += s[0] * s[1];
            m11 += s[1] * s[1];
        }
        double cr1 = g <= 1 ? 1.0 : (double) g / (g - 1);
        m00 *= cr1;
        m01 *= cr1;
        m11 *= cr1;

        // V = B M B，取 [1,1]
        double vb11 = b01 * b01 * m00 + 2 * b01 * b11 * m01 + b11 * b11 * m11;
        double se = Math.sqrt(Math.max(vb11, 0));
        double df = Math.max(1, g - 1);
        double tcrit = tCritical(df);
        double est = beta1;
        return new EffectEstimate(est, se, est - tcrit * se, est + tcrit * se,
                2 * normalTwoSidedP(Math.abs(est / (se == 0 ? Double.NaN : se))),
                n, g, df);
    }

    /** 两个 ITT 系数（结果 y 与第一阶段 D）的聚类方差/协方差，供 Wald/CACE delta 方法使用。 */
    public static double[] clusterCovariancePair(List<Observation> yObs, List<Observation> dObs) {
        // 要求顺序/分组一致；分别求 beta 与每组 score，再算交叉 meat
        Fit fy = wls(yObs);
        Fit fd = wls(dObs);
        Map<String, double[]> sy = scores(yObs, fy);
        Map<String, double[]> sd = scores(dObs, fd);
        int g = sy.size();
        double cr1 = g <= 1 ? 1.0 : (double) g / (g - 1);

        double myy00 = 0, myy01 = 0, myy11 = 0;
        double mdd00 = 0, mdd01 = 0, mdd11 = 0;
        double myd00 = 0, myd01 = 0, myd10 = 0, myd11 = 0;
        for (String key : sy.keySet()) {
            double[] a = sy.get(key);
            double[] b = sd.getOrDefault(key, new double[2]);
            myy00 += a[0] * a[0];
            myy01 += a[0] * a[1];
            myy11 += a[1] * a[1];
            mdd00 += b[0] * b[0];
            mdd01 += b[0] * b[1];
            mdd11 += b[1] * b[1];
            myd00 += a[0] * b[0];
            myd01 += a[0] * b[1];
            myd10 += a[1] * b[0];
            myd11 += a[1] * b[1];
        }
        myy00 *= cr1; myy01 *= cr1; myy11 *= cr1;
        mdd00 *= cr1; mdd01 *= cr1; mdd11 *= cr1;
        myd00 *= cr1; myd01 *= cr1; myd10 *= cr1; myd11 *= cr1;

        double[] by = fy.bread();
        double[] bd = fd.bread();
        // bread 以 2x2 展开: b00,b01,b11
        double vy11 = by[1] * by[1] * myy00 + 2 * by[1] * by[2] * myy01 + by[2] * by[2] * myy11;
        double vd11 = bd[1] * bd[1] * mdd00 + 2 * bd[1] * bd[2] * mdd01 + bd[2] * bd[2] * mdd11;
        double cov11 = by[1] * bd[1] * myd00 + by[1] * bd[2] * myd01
                + by[2] * bd[1] * myd10 + by[2] * bd[2] * myd11;
        double betaY = fy.beta1();
        double betaD = fd.beta1();

        double wald = betaD == 0 ? Double.NaN : betaY / betaD;
        // delta: Var(y/d)
        double varWald = betaD == 0 ? Double.NaN
                : vy11 / (betaD * betaD)
                + betaY * betaY * vd11 / Math.pow(betaD, 4)
                - 2 * betaY * cov11 / Math.pow(betaD, 3);
        double seWald = Math.sqrt(Math.max(varWald, 0));
        return new double[]{betaY, betaD, cov11, vy11, vd11, wald, seWald};
    }

    private record Fit(double beta0, double beta1, double[] bread) {
    }

    private static Fit wls(List<Observation> obs) {
        double s00 = 0, s01 = 0, s11 = 0, v0 = 0, v1 = 0;
        for (Observation o : obs) {
            double w = o.w();
            s00 += w;
            s01 += w * o.a();
            s11 += w * o.a() * o.a();
            v0 += w * o.y();
            v1 += w * o.a() * o.y();
        }
        double det = s00 * s11 - s01 * s01;
        double b00 = s11 / det;
        double b01 = -s01 / det;
        double b11 = s00 / det;
        return new Fit(b00 * v0 + b01 * v1, b01 * v0 + b11 * v1, new double[]{b00, b01, b11});
    }

    private static Map<String, double[]> scores(List<Observation> obs, Fit fit) {
        Map<String, double[]> map = new LinkedHashMap<>();
        for (Observation o : obs) {
            double e = o.y() - (fit.beta0() + fit.beta1() * o.a());
            double[] s = map.computeIfAbsent(o.group(), k -> new double[2]);
            s[0] += o.w() * e;
            s[1] += o.w() * o.a() * e;
        }
        return map;
    }

    /** 单组加权均值的 iid 稳健标准误（描述性路径结果用，非随机化推断）。 */
    public static double weightedMeanSe(List<Double> values, List<Double> weights, double mean) {
        double sw = 0;
        for (double w : weights) {
            sw += w;
        }
        double meat = 0;
        int n = values.size();
        for (int i = 0; i < n; i++) {
            double e = values.get(i) - mean;
            meat += weights.get(i) * weights.get(i) * e * e;
        }
        if (sw == 0 || n < 2) {
            return Double.NaN;
        }
        double factor = (double) n / (n - 1);
        return Math.sqrt(factor * meat) / sw;
    }

    public static double[] wilson(long x, long n, double z) {
        if (n == 0) {
            return new double[]{0, 0, 0};
        }
        double phat = x / (double) n;
        double denom = 1 + z * z / n;
        double center = (phat + z * z / (2 * n)) / denom;
        double half = z * Math.sqrt(phat * (1 - phat) / n + z * z / (4 * n * n)) / denom;
        return new double[]{phat, Math.max(0, center - half), Math.min(1, center + half)};
    }

    /** 常用 t 分布 0.975 分位数；df 为连续值时按 1/df 线性插值。 */
    public static double tCritical(double df) {
        if (df <= 1) return 12.706;
        int floor = (int) Math.floor(df);
        double frac = df - floor;
        double lo = tTable(floor);
        double hi = tTable(floor + 1);
        return lo + (hi - lo) * frac;
    }

    private static double tTable(int df) {
        double[] table = {
                12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, // 1-10
                2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, // 11-20
                2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042  // 21-30
        };
        if (df >= 1 && df <= 30) {
            return table[df - 1];
        }
        if (df <= 40) return 2.021;
        if (df <= 60) return 2.000;
        if (df <= 120) return 1.980;
        return 1.960;
    }

    /** 标准正态双侧 p 值。 */
    public static double normalTwoSidedP(double z) {
        if (Double.isNaN(z)) return Double.NaN;
        return 2 * (1 - 0.5 * (1 + erf(Math.abs(z) / Math.sqrt(2))));
    }

    // Abramowitz-Stegun erf 近似
    private static double erf(double x) {
        double sign = x >= 0 ? 1 : -1;
        x = Math.abs(x);
        double a1 = 0.254829592, a2 = -0.284496736, a3 = 1.421413741;
        double a4 = -1.453152027, a5 = 1.061405429, p = 0.3275911;
        double t = 1 / (1 + p * x);
        double y = 1 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        return sign * y;
    }

    /** 随机化推断：枚举每周方向位，计算所有置换下的加权均值差，返回 [p, observed]。 */
    public static double[] randomizationInferenceClusterWeeks(
            List<RiRow> rows, List<int[]> allOrientations, int[] observedOrientation) {
        double observed = riStatistic(rows, observedOrientation);
        int ge = 0;
        for (int[] ori : allOrientations) {
            double s = riStatistic(rows, ori);
            if (Math.abs(s) >= Math.abs(observed) - 1e-9) {
                ge++;
            }
        }
        double p = (1 + ge) / (double) (allOrientations.size() + 1);
        return new double[]{p, observed};
    }

    /** RI 行：week(0..3)、orientation 下该簇在该周是否处理由 ori[week] 与 clusterIndex 决定。 */
    public record RiRow(int week, int clusterIndex, double y, double w) {
    }

    private static double riStatistic(List<RiRow> rows, int[] orientation) {
        // orientation[w]=0 表示簇0处理、簇1控制；1 表示簇1处理
        List<Double> yt = new ArrayList<>(), wt = new ArrayList<>();
        List<Double> yc = new ArrayList<>(), wc = new ArrayList<>();
        for (RiRow r : rows) {
            boolean clusterZeroTreated = orientation[r.week()] == 0;
            boolean treated = (r.clusterIndex() == 0) == clusterZeroTreated;
            if (treated) {
                yt.add(r.y());
                wt.add(r.w());
            } else {
                yc.add(r.y());
                wc.add(r.w());
            }
        }
        return weightedMean(yt, wt) - weightedMean(yc, wc);
    }
}
