package com.ccdev.transit.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkClustererTest {

    private final NetworkClusterer clusterer = new NetworkClusterer();

    @Test
    void r1AndR2WithFortyPercentTransferClusterTogether_r3StaysSeparate() {
        var edges = List.of(
                new NetworkClusterer.TransferEdge("R1", "R2", 0.40),
                new NetworkClusterer.TransferEdge("R2", "R1", 0.38),
                new NetworkClusterer.TransferEdge("R1", "R3", 0.012),
                new NetworkClusterer.TransferEdge("R3", "R1", 0.018),
                new NetworkClusterer.TransferEdge("R2", "R3", 0.016),
                new NetworkClusterer.TransferEdge("R3", "R2", 0.011));

        NetworkClusterer.ClusterPlan plan =
                clusterer.cluster(List.of("R1", "R2", "R3"), edges, 0.15);

        assertEquals(2, plan.clusters().size());
        Set<String> first = plan.clusters().get(0);
        assertEquals(Set.of("R1", "R2"), first);
        assertEquals(Set.of("R3"), plan.clusters().get(1));
    }

    @Test
    void belowThresholdEdgesDoNotConnect() {
        var edges = List.of(new NetworkClusterer.TransferEdge("R1", "R2", 0.10));
        var plan = clusterer.cluster(List.of("R1", "R2"), edges, 0.15);
        assertEquals(2, plan.clusters().size());
    }
}
