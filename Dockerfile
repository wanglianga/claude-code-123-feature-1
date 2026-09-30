# ---- 阶段1：构建前端（产物输出到后端 static 目录） ----
FROM node:20-alpine AS frontend
WORKDIR /build
# Vite 配置的输出目录为 ../backend/src/main/resources/static，先建好占位目录
RUN mkdir -p /build/backend/src/main/resources/static
COPY frontend/package.json frontend/package-lock.json* ./frontend/
RUN cd frontend && npm install --cache /tmp/npm-cache
COPY frontend ./frontend
RUN cd frontend && npm run build

# ---- 阶段2：构建后端（单 jar，内含前端静态资源） ----
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build
COPY backend/pom.xml ./backend/
# 预热 Maven 依赖（失败不阻断，后续 package 会补全）
RUN cd backend && (mvn -B -q dependency:go-offline || true)
COPY backend ./backend
# 以前端构建产物为准，避免仓库内历史 hash 资源残留
RUN rm -rf ./backend/src/main/resources/static/assets
COPY --from=frontend /build/backend/src/main/resources/static ./backend/src/main/resources/static
RUN cd backend && mvn -B -q clean package -DskipTests

# ---- 阶段3：运行 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=backend /build/backend/target/mediation-platform.jar /app/mediation-platform.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/mediation-platform.jar"]
