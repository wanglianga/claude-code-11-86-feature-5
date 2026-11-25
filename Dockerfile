# syntax=docker/dockerfile:1

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 容器内使用阿里云 Maven 镜像
COPY docker-settings.xml /root/.m2/settings.xml
COPY pom.xml ./pom.xml
RUN mvn -B -ntp dependency:go-offline || true
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
COPY --from=build /build/target/transit-pilot.jar /app/transit-pilot.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD bash -c 'echo > /dev/tcp/127.0.0.1/8080' || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/transit-pilot.jar"]
