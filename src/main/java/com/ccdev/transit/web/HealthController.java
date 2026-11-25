package com.ccdev.transit.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            m.put("db", one != null && one == 1 ? "UP" : "UNKNOWN");
        } catch (Exception e) {
            m.put("db", "DOWN: " + e.getMessage());
        }
        return m;
    }
}
