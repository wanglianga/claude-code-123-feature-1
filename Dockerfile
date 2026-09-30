# 多阶段构建：前端 Vite 构建 -> 后端 Maven 打包 -> JRE 精简运行
# 一键构建镜像：docker build -t court-mediation .
# 一键启动：docker compose up -d  （或 docker run -p 8080:8080 court-mediation）

# ---------- 1) 前端构建，产物输出到后端 static ----------
FROM node:20-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm install --cache /tmp/npm-cache
COPY frontend/ ./
RUN npm run build

# ---------- 2) 后端打包（前端产物已落在 src/main/resources/static） ----------
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
# 用前端构建产物覆盖 static，保证 jar 内为最新页面
COPY --from=frontend /build/backend/src/main/resources/static ./src/main/resources/static
RUN mvn -q -B clean package -DskipTests

# ---------- 3) 运行镜像 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=backend /build/backend/target/mediation-platform.jar /app/mediation-platform.jar
EXPOSE 8080
# 默认 H2 内存库，零外部依赖；可用 --spring.profiles.active=mysql 切换 MySQL
ENTRYPOINT ["java", "-jar", "/app/mediation-platform.jar"]
