package com.ccdev.transit.engine;

import java.util.List;

/** 每条线路每个运营日 12 个计划班次模板：4 早高峰 / 4 晚高峰 / 4 平峰。 */
public final class TripTemplates {

    public record TemplateSpec(String code, String band, int seq, int hour, int minute) {
    }

    public static final List<TemplateSpec> ALL = List.of(
            new TemplateSpec("AM1", "AM_PEAK", 1, 7, 0),
            new TemplateSpec("AM2", "AM_PEAK", 2, 7, 20),
            new TemplateSpec("AM3", "AM_PEAK", 3, 7, 40),
            new TemplateSpec("AM4", "AM_PEAK", 4, 8, 0),
            new TemplateSpec("PM1", "PM_PEAK", 5, 17, 0),
            new TemplateSpec("PM2", "PM_PEAK", 6, 17, 20),
            new TemplateSpec("PM3", "PM_PEAK", 7, 17, 40),
            new TemplateSpec("PM4", "PM_PEAK", 8, 18, 0),
            new TemplateSpec("OP1", "OFF_PEAK", 9, 10, 0),
            new TemplateSpec("OP2", "OFF_PEAK", 10, 11, 5),
            new TemplateSpec("OP3", "OFF_PEAK", 11, 14, 10),
            new TemplateSpec("OP4", "OFF_PEAK", 12, 15, 0)
    );

    public static List<TemplateSpec> byBand(String band) {
        return ALL.stream().filter(t -> t.band().equals(band)).toList();
    }

    private TripTemplates() {
    }
}
