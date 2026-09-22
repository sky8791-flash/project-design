# Build the runnable jar, then run it with a thin runtime image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/online-collab-doc-1.0.0.jar app.jar
EXPOSE 8080
# Spring Boot 3 exposes graceful shutdown only when it is asked for.
ENV JAVA_TOOL_OPTIONS="" \
    SERVER_SHUTDOWN=graceful \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=30s
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
