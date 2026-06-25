# ============================================================
# DevOps Continuous Delivery Platform - Multi-stage Dockerfile
# ============================================================
# Build context: workspace root (contains frontend/ and devops-platform/)
# All base images use DaoCloud mirror (docker.m.daocloud.io) which
# mirrors ALL of DockerHub (official + third-party) and is reliably
# accessible from mainland China without authentication.
# ============================================================

# ---------- Stage 1: Build Frontend ----------
FROM docker.m.daocloud.io/library/node:22-slim AS frontend-builder

WORKDIR /app/frontend

# Use npmmirror.com (Alibaba) — reliable npm mirror inside Docker.
RUN npm config set registry https://registry.npmmirror.com && \
    echo "registry=https://registry.npmmirror.com" > .npmrc

# Copy package.json only (omit lock file to avoid stale upstream URLs).
COPY frontend/package.json ./

# --legacy-peer-deps: React 19 + react-router-dom 7 peer-dep workaround
RUN npm install --legacy-peer-deps --no-audit --no-fund --prefer-online

# Copy frontend source and build
COPY frontend/ ./
RUN npm run build -- --outDir dist

# ---------- Stage 2: Build Backend ----------
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 AS backend-builder

WORKDIR /app/devops-platform

# Use Aliyun Maven mirror for faster dependency downloads
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

# Copy Maven project files first (for layer caching)
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
FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre-alpine

LABEL maintainer="devops-platform"
LABEL description="DevOps Continuous Delivery Platform"

WORKDIR /app

# Install curl for healthcheck
RUN apk add --no-cache curl

# Copy the built jar
COPY --from=backend-builder /app/devops-platform/target/devops-platform-*.jar app.jar

# Create data directories
RUN mkdir -p /app/data/workspace /app/data/logs

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/api/dashboard/stats || exit 1

ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
