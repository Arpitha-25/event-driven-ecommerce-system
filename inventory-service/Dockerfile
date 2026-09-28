# syntax=docker/dockerfile:1
# Build from the repository root, which has the parent POM and common-module:
#   docker build -f inventory-service/Dockerfile -t ecommerce/inventory-service .
# (docker compose does this for you.)

# ---- Build stage: compile the service with Maven inside Docker ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Maven needs every module's POM listed in the parent, but only this service's sources.
COPY pom.xml .
COPY common-module/pom.xml common-module/
COPY order-service/pom.xml order-service/
COPY product-service/pom.xml product-service/
COPY inventory-service/pom.xml inventory-service/
COPY common-module/src common-module/src
COPY inventory-service/src inventory-service/src

# The Maven repository is a BuildKit cache shared by all service images and all rebuilds,
# so dependencies download once. Tests aren't compiled, so test-only libraries aren't needed.
# "locked": parallel builds take turns, because a Maven repository isn't safe for concurrent writes.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl inventory-service -am package -Dmaven.test.skip=true

# ---- Runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /workspace/inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8083
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
