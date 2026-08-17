# syntax=docker/dockerfile:1

FROM amazoncorretto:21-al2023 AS build
WORKDIR /workspace
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon dependencies --configuration runtimeClasspath

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

FROM amazoncorretto:21-al2023
RUN dnf install -y redis6-6.2.* \
    && dnf clean all \
    && rm -rf /var/cache/dnf

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# App HTTP (7079) and management (7081). Redis is NOT exposed — bound to 127.0.0.1 inside the container.
EXPOSE 7079 7081

ENV SPRING_PROFILES_ACTIVE=docker

ENTRYPOINT ["/entrypoint.sh"]
