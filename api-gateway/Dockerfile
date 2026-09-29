# syntax=docker/dockerfile:1
# Build from the repository root, which has the parent POM:
#   docker build -f api-gateway/Dockerfile -t ecommerce/api-gateway .
# (docker compose does this for you.)

# ---- Build stage: compile the gateway with Maven inside Docker ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Maven needs every module's POM listed in the parent. The gateway doesn't use common-module.
COPY pom.xml .
COPY common-module/pom.xml common-module/
COPY order-service/pom.xml order-service/
COPY product-service/pom.xml product-service/
COPY inventory-service/pom.xml inventory-service/
COPY identity-service/pom.xml identity-service/
COPY api-gateway/pom.xml api-gateway/
COPY api-gateway/src api-gateway/src

# Shared BuildKit Maven cache, as in the service images.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl api-gateway -am package -Dmaven.test.skip=true

# ---- Runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /workspace/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
