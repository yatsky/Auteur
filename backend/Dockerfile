# Backend Dockerfile — multi-stage
# Stage 1: Maven build (eclipse-temurin 21 + maven 3)
# Stage 2: JRE 21 runtime + ffmpeg + Noto CJK 字体（字幕烧录用）

# ──────────────────────────────────────────
# Stage 1: build
# ──────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 先拷 pom 让 maven 缓存依赖（这一层只在 pom 改动时失效，加快迭代构建）
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# 再拷源码并打包（跳测试，CI 单独跑）
COPY src ./src
RUN mvn -B -q -DskipTests package \
    && mv target/auteur-backend-*.jar target/app.jar

# ──────────────────────────────────────────
# Stage 2: runtime
# ──────────────────────────────────────────
FROM eclipse-temurin:21-jre

# ffmpeg + 中文字体（字幕烧录走 libass，必须有 CJK 字体）+ curl（healthcheck）
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ffmpeg \
       fonts-noto-cjk \
       curl \
       tzdata \
    && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Shanghai
ENV SPRING_PROFILES_ACTIVE=docker
ENV JAVA_OPTS=""

WORKDIR /app
COPY --from=build /workspace/target/app.jar /app/app.jar

# storage 目录由 docker-compose 卷挂载，这里只建路径
RUN mkdir -p /app/storage/voice /app/storage/video /app/storage/cover /app/storage/bgm /app/storage/sfx

EXPOSE 8082

# Spring Boot Actuator /actuator/health 是标准探活端点
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://localhost:8082/actuator/health || exit 1

# Wait for the MySQL sidecar before starting (avoids the startup race where Spring
# Boot fails fast on first boot / box reboot while MySQL is still initializing).
ENTRYPOINT ["bash", "-c", "host=${MYSQL_HOST:-mysql}; port=${MYSQL_PORT:-3306}; echo \"[entrypoint] waiting for $host:$port ...\"; for i in $(seq 1 120); do (echo > /dev/tcp/$host/$port) >/dev/null 2>&1 && { echo \"[entrypoint] $host:$port is up\"; break; }; sleep 2; done; sleep 3; exec java $JAVA_OPTS -jar /app/app.jar"]
