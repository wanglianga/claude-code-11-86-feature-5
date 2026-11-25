package com.ccdev.transit.config;

import com.ccdev.transit.util.Json;
import org.postgresql.util.PGobject;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JdbcTemplate 的 Map 结果里 jsonb 可能是 String 或 PGobject；
 * 在响应写出前把这些已知 JSON 列统一还原成对象/数组，API 直接返回结构化 JSON。
 */
@RestControllerAdvice
public class JsonbResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final Set<String> JSON_KEYS = Set.of(
            "treatment_rules", "primary_metrics", "safety_metrics",
            "network_config", "analysis_config", "detail", "evidence", "path_detail",
            "support_links");

    @Override
    public boolean supports(MethodParameter returnType,
                           Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        return transform(body, null);
    }

    @SuppressWarnings("unchecked")
    private Object transform(Object node, String key) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), transform(v, String.valueOf(k))));
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object v : list) {
                out.add(transform(v, key));
            }
            return out;
        }
        if (node instanceof PGobject pg) {
            String v = pg.getValue();
            if (v != null && looksJson(v)) {
                try {
                    return Json.MAPPER.readValue(v, Object.class);
                } catch (Exception ignored) {
                    return v;
                }
            }
            return v;
        }
        if (node instanceof String s && key != null && JSON_KEYS.contains(key) && looksJson(s)) {
            try {
                return Json.MAPPER.readValue(s, Object.class);
            } catch (Exception ignored) {
                return s;
            }
        }
        return node;
    }

    private boolean looksJson(String v) {
        String t = v.trim();
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"));
    }
}
