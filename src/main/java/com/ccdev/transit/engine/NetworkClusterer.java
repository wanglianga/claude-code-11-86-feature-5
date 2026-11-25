package com.ccdev.transit.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 依据“实验前”刷卡换乘边比例构造固定连接关系并求连通簇。
 * 阈值判定使用有向边任一方向达到阈值（调用方也可先合并方向）。
 * 簇在实验登记时冻结，试点结果不得触发重新拆簇/并簇。
 */
public final class NetworkClusterer {

    public record TransferEdge(String fromRoute, String toRoute, double ratio) {
    }

    public record ClusterPlan(List<Set<String>> clusters, boolean[][] adjacency, List<String> routes) {
        public String clusterOf(String route) {
            for (Set<String> c : clusters) {
                if (c.contains(route)) {
                    return "C" + (clusters.indexOf(c) + 1);
                }
            }
            return null;
        }
    }

    public ClusterPlan cluster(Collection<String> allRoutes, List<TransferEdge> edges, double edgeThreshold) {
        List<String> routes = new ArrayList<>(new TreeSet<>(allRoutes));
        int n = routes.size();
        UnionFind uf = new UnionFind(n);
        boolean[][] adj = new boolean[n][n];

        for (TransferEdge e : edges) {
            int i = routes.indexOf(e.fromRoute());
            int j = routes.indexOf(e.toRoute());
            if (i < 0 || j < 0 || i == j) {
                continue;
            }
            if (e.ratio() >= edgeThreshold) {
                adj[i][j] = adj[j][i] = true;
                uf.union(i, j);
            }
        }

        java.util.Map<Integer, Set<String>> grouped = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = uf.find(i);
            grouped.computeIfAbsent(root, k -> new LinkedHashSet<>()).add(routes.get(i));
        }
        List<Set<String>> clusters = new ArrayList<>();
        for (Set<String> s : grouped.values()) {
            clusters.add(new TreeSet<>(s));
        }
        clusters.sort((a, b) -> {
            String xa = String.join(",", a);
            String xb = String.join(",", b);
            return xa.compareTo(xb);
        });
        return new ClusterPlan(clusters, adj, routes);
    }

    private static final class UnionFind {
        private final int[] parent;

        UnionFind(int n) {
            parent = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
            }
        }

        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        void union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra != rb) {
                parent[rb] = ra;
            }
        }
    }
}
