# ============================================================
# DevOps Continuous Delivery Platform - Multi-stage Dockerfile
# ============================================================
# Build context: workspace root (contains frontend/ and devops-platform/)
# ============================================================

# ---------- Stage 1: Build Frontend ----------
# node:22-slim → 阿里云镜像（Docker Hub 国内被墙）
FROM registry.cn-hangzhou.aliyuncs.com/library/node:22-slim AS frontend-builder

WORKDIR /app/frontend

# Use npmmirror.com (Alibaba) — more reliable inside Docker than Huawei mirror.
RUN npm config set registry https://registry.npmmirror.com

# Write .npmrc so the mirror also applies to child processes.
RUN echo "registry=https://registry.npmmirror.com" > .npmrc

# Copy package.json. Purposely omit package-lock.json — let npm resolve
# fresh against the mirror instead of replaying stale upstream URLs.
COPY frontend/package.json ./

# --legacy-peer-deps: React 19 + react-router-dom 7 peer-dep workaround
# --prefer-online: always fetch from registry (no cache poison)
RUN npm install --legacy-peer-deps --no-audit --no-fund --prefer-online

# Copy frontend source
COPY frontend/ ./

# Build React app -> outputs to /app/frontend/dist
RUN npm run build -- --outDir dist

# ---------- Stage 2: Build Backend ----------
# maven:3.9-eclipse-temurin-21 → 阿里云镜像
FROM registry.cn-hangzhou.aliyuncs.com/library/maven:3.9-eclipse-temurin-21 AS backend-builder

WORKDIR /app/devops-platform

# Use Aliyun Maven mirror for faster, more reliable dependency downloads
RUN mkdir -p /root/.m2 && cat > /root/.m2/settings.xml <<'EOF'
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF

# Copy Maven project files first (for dependency caching)
COPY devops-platform/pom.xml ./
RUN mvn dependency:go-offline -B

# Copy backend source
COPY devops-platform/src ./src
COPY devops-platform/sql ./sql

# Copy frontend build output into Spring Boot static resources
COPY --from=frontend-builder /app/frontend/dist ./src/main/resources/static

# Build the Spring Boot jar
RUN mvn clean package -DskipTests -B

# ---------- Stage 3: Runtime ----------
# eclipse-temurin:21-jre-alpine → 阿里云镜像
FROM registry.cn-hangzhou.aliyuncs.com/library/eclipse-temurin:21-jre-alpine

LABEL maintainer="devops-platform"
LABEL description="DevOps Continuous Delivery Platform"

WORKDIR /app

# Install curl for healthcheck (alpine image does not ship it)
RUN apk add --no-cache curl

# Copy the built jar
COPY --from=backend-builder /app/devops-platform/target/devops-platform-*.jar app.jar

# Create data directories
RUN mkdir -p /app/data/workspace /app/data/logs

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/api/dashboard/stats || exit 1

# JVM options for container
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
