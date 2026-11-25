package com.ccdev.transit.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/** 让 PostgreSQL jsonb 列直接输出为 JSON 对象/数组，而不是转义字符串或 PGobject Bean。 */
@Configuration
public class JacksonConfig {

    @Bean
    public Module pgObjectJsonModule() {
        SimpleModule module = new SimpleModule("pgobject-json");
        module.addSerializer(PGobject.class, new JsonSerializer<>() {
            @Override
            public void serialize(PGobject value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                String v = value.getValue();
                if (v == null) {
                    gen.writeNull();
                    return;
                }
                String type = value.getType();
                boolean jsonType = type != null && type.toLowerCase().contains("json");
                if (jsonType || looksLikeJson(v)) {
                    try {
                        gen.writeRawValue(v);
                        return;
                    } catch (Exception ignored) {
                        // 落到普通字符串输出
                    }
                }
                gen.writeString(v);
            }

            private boolean looksLikeJson(String v) {
                String t = v.trim();
                return (t.startsWith("{") && t.endsWith("}"))
                        || (t.startsWith("[") && t.endsWith("]"));
            }
        });
        return module;
    }
}
