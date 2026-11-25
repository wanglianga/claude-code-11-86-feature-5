package com.ccdev.transit.service;

import com.ccdev.transit.util.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扩围证据决策：护栏独立判定，任一硬护栏超限 -> BLOCKED（停止扩围）；
 * 证据不足 -> HOLD；全部通过 -> GO。存在未消除超限时，人工也不能签 GO。
 */
@Service
public class ExpansionService {

    private final JdbcTemplate jdbc;
    private final PipelineService pipeline;

    public ExpansionService(JdbcTemplate jdbc, PipelineService pipeline) {
        this.jdbc = jdbc;
        this.pipeline = pipeline;
    }

    public Map<String, Object> evaluateAndDecide(long expId, String username) {
        List<Map<String, Object>> evals = pipeline.evaluateGuardrails(expId);

        boolean blocked = evals.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("breached")));
        boolean insufficient = evals.stream()
                .anyMatch(e -> "INSUFFICIENT_EVIDENCE".equals(e.get("action")));

        String decision = blocked ? "BLOCKED" : insufficient ? "HOLD" : "GO";
        String reason;
        if (blocked) {
            List<String> codes = evals.stream()
                    .filter(e -> Boolean.TRUE.equals(e.get("breached")))
                    .map(e -> String.valueOf(e.get("ruleCode"))).toList();
            reason = "护栏 " + codes + " 观测值越过冻结阈值，按规则立即停止扩围；"
                    + "平均候车改善不能抵消投诉/满载/安全护栏超限。";
        } else if (insufficient) {
            reason = "部分护栏样本不足，证据不充分，暂不扩围（HOLD）。";
        } else {
            reason = "主要指标与全部安全护栏均满足预设标准，可进入扩围评审。";
        }

        List<Map<String, Object>> primary = jdbc.queryForList("""
                SELECT design_type, estimator_code, metric_code, scope, estimate, ci_low, ci_high, p_value
                FROM analysis_result
                WHERE experiment_id=? AND estimator_code='ITT' AND scope='COMMON_SUPPORT'
                ORDER BY design_type, metric_code
                """, expId);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("guardrails", evals);
        evidence.put("primaryItt", primary);
        evidence.put("rule", "任一硬护栏 BREACHED => BLOCKED，且不允许人工改判 GO");

        jdbc.update("""
                INSERT INTO expansion_decision(experiment_id,decision,reason,evidence,decided_by,created_at)
                VALUES (?,?,?,?::jsonb,?, now())
                """, expId, decision, reason, Json.write(evidence), username);

        return latestDecision(expId);
    }

    /** 人工记录 HOLD/BLOCKED；护栏超限时拒绝 GO。 */
    public Map<String, Object> manualDecide(long expId, String decision, String reason, String username) {
        if ("GO".equals(decision)) {
            Integer breach = jdbc.queryForObject("""
                    SELECT count(*) FROM guardrail_evaluation ge
                    WHERE ge.experiment_id=? AND ge.breached=TRUE
                      AND ge.id = (SELECT max(id) FROM guardrail_evaluation
                                   WHERE experiment_id=ge.experiment_id AND rule_code=ge.rule_code)
                    """, Integer.class, expId);
            if (breach != null && breach > 0) {
                throw new IllegalStateException("存在 " + breach + " 条已超限护栏，按规则不得签署 GO 扩围");
            }
        }
        Map<String, Object> evidence = Map.of("manual", true);
        jdbc.update("""
                INSERT INTO expansion_decision(experiment_id,decision,reason,evidence,decided_by,created_at)
                VALUES (?,?,?,?::jsonb,?, now())
                """, expId, decision, reason == null ? "" : reason, Json.write(evidence), username);
        return latestDecision(expId);
    }

    public Map<String, Object> latestDecision(long expId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM expansion_decision WHERE experiment_id=? ORDER BY id DESC LIMIT 1", expId);
        if (rows.isEmpty()) {
            return Map.of("decision", "NOT_EVALUATED");
        }
        Map<String, Object> row = rows.get(0);
        if (row.get("evidence") != null) {
            try {
                row.put("evidence", Json.read(String.valueOf(row.get("evidence")), Map.class));
            } catch (Exception ignored) {
                // 保留字符串
            }
        }
        return row;
    }

    public List<Map<String, Object>> decisions(long expId) {
        return jdbc.queryForList(
                "SELECT id,decision,reason,decided_by,created_at FROM expansion_decision "
                        + "WHERE experiment_id=? ORDER BY id DESC", expId);
    }
}
