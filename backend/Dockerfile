# ClearRead API — multi-stage build.
# Gradle distribution comes from gradle:8.14.3-jdk21 (/opt/gradle) — no
# services.gradle.org zip download via the wrapper during docker build.
#
# Do NOT BuildKit-cache-mount the entire GRADLE_USER_HOME: on Windows Docker
# Desktop that often corrupts/permissions native-platform (libnative-platform.so),
# especially after USER gradle/root mixes. Cache only caches/ + wrapper/.
# Host/wrapper exit 127 is still usually a missing +x bit or CRLF after checkout.
FROM eclipse-temurin:21-jdk AS build

COPY --from=gradle:8.14.3-jdk21 /opt/gradle /opt/gradle
ENV PATH="/opt/gradle/bin:${PATH}" \
    GRADLE_USER_HOME=/root/.gradle

WORKDIR /workspace

# Single consistent user (root) for COPY + RUN — avoids USER gradle/root cache traps.
COPY gradle/wrapper/ gradle/wrapper/
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
# Keep wrapper files for host builds; Docker uses /opt/gradle from the Gradle image.
RUN chmod +x gradlew && sed -i 's/\r$//' gradlew

# Cache dependency/wrapper dirs only — never native libs under GRADLE_USER_HOME root.
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    gradle --version --no-daemon \
 && gradle dependencies --no-daemon -q || true

COPY src/ src/
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=build /workspace/build/libs/clearread-api.jar /app/clearread-api.jar
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=45s --retries=12 \
  CMD curl -sf http://127.0.0.1:8080/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/clearread-api.jar"]
