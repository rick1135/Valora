FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml pom.xml
RUN chmod +x mvnw
RUN ./mvnw -q -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system valora && useradd --system --gid valora --no-create-home valora

COPY --from=build --chown=valora:valora /app/target/*.jar /app/app.jar

USER valora

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
