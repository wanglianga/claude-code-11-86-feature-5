package com.ccdev.transit.util;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 成本结果记录统一输出 snake_case，与 JDBC 行/API 既有风格保持一致。 */
@JacksonAnnotationsInside
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SnakeJson {
}
