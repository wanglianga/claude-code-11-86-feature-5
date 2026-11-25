package com.ccdev.transit.web.dto;

import java.util.List;

/** 新建/登记实验请求。routes 与 preEdges 让调用方提交实验前冻结网络。 */
public record ExperimentRequest(
        String code,
        String name,
        String activeDesign,
        String seed,
        Double assignmentProb,
        Integer pilotWeeks,
        String startDate,
        String targetPopulation,
        String treatmentRules,
        Double edgeThreshold,
        Integer minSupportPerArm,
        List<RouteDto> routes,
        List<EdgeDto> preEdges) {

    public record RouteDto(String code, String name) {
    }

    public record EdgeDto(String fromRoute, String toRoute,
                         Integer transferJourneys, Integer totalJourneys) {
    }
}
