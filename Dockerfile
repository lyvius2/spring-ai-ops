# syntax=docker/dockerfile:1

FROM ghcr.io/graalvm/native-image-community:21-ol9 AS build
RUN microdnf install -y findutils && microdnf clean all
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src/main ./src/main
ARG NATIVE_IMAGE_HEAP=8g
# No test sources; JVM tests excluded and native test support disabled in Gradle.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx512m \
    -Pkotlin.compiler.execution.strategy=in-process -PnativeImageHeap=${NATIVE_IMAGE_HEAP} \
    nativeCompile -PcontainerBuild -x test
RUN mkdir -p /runtime/data/repository

# -H:+StaticExecutableWithDynamicLibC requires glibc only. Includes CA certificates and timezone data.
FROM gcr.io/distroless/base-nossl-debian12:nonroot
WORKDIR /app
COPY --from=build --chown=65532:65532 /runtime/ /app/
COPY --from=build --chown=65532:65532 /workspace/build/native/nativeCompile/spring-ai-ops /app/spring-ai-ops
ENV SPRING_PROFILES_ACTIVE=docker
EXPOSE 7079 7081
USER 65532:65532
# Only the production binary: no JVM, shell, Gradle or test runner.
ENTRYPOINT ["/app/spring-ai-ops"]
