# ---- build stage: compiles and packages (tests run via `docker compose run --rm tests`) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- runtime stage ----
# JDK (not JRE): the unit-test agent compiles generated sources with javax.tools at run time
FROM eclipse-temurin:21-jdk
RUN useradd --system --create-home app
WORKDIR /app
COPY --from=build /build/target/agentic-url-shortener-1.0.0.jar app.jar
# read-only copy of the source tree for the codebase-analysis agent (brownfield reasoning)
COPY src/main ./source
RUN mkdir -p /app/workspace && chown -R app:app /app
USER app
ENV CODEBASE_ROOT=/app/source WORKSPACE_ROOT=/app/workspace JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
