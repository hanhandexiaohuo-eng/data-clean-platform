# ============================================================
# 后端镜像（多阶段构建）
# ------------------------------------------------------------
# 基础镜像策略：
#   - 构建阶段固定 Maven 3.9 + JDK 17 LTS；
#   - 运行阶段固定 JRE 17 LTS，不使用 latest；
#   - 升级策略：随 JDK LTS / Spring Boot 基线升级，升级前重跑
#     后端测试、迁移演练与镜像冒烟。
#
# 安全：
#   - 不复制真实 application.yml/.env/上传文件/备份/密钥；
#   - 使用镜像内的 application.yml.example 作为配置骨架（仅环境变量占位）；
#   - 以非 root 用户运行，上传/日志目录仅授予该用户写权限。
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 国内构建镜像源（无凭据），避免 Maven Central 拉取缓慢
COPY deploy/maven/settings.xml /root/.m2/settings.xml

COPY backend/pom.xml ./
RUN mvn -B -q -Dmaven.test.skip=true dependency:go-offline

COPY backend/src ./src
# 真实 application.yml 已被 .dockerignore 排除；用脱敏模板作为镜像内配置骨架
RUN cp src/main/resources/application.yml.example src/main/resources/application.yml \
    && mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache tzdata \
    && addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app app \
    && mkdir -p /app/uploads /app/logs \
    && chown -R app:app /app

WORKDIR /app

COPY --from=build --chown=app:app /build/target/*.jar /app/app.jar

ENV TZ=Asia/Shanghai \
    SERVER_PORT=8080 \
    UPLOAD_PATH=/app/uploads \
    LOG_PATH=/app/logs

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
