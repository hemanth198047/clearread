# ClearRead API — multi-stage build on official Gradle image (distribution preinstalled).
# Avoids downloading gradle-*-bin.zip from services.gradle.org during docker build
# (common SocketTimeoutException / networkTimeout on Windows Docker Desktop).
# Host/wrapper exit 127 is still usually a missing +x bit or CRLF after checkout.
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /home/gradle/project

USER root
COPY --chown=gradle:gradle gradle/wrapper/ gradle/wrapper/
COPY --chown=gradle:gradle gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
# Keep wrapper files for host builds; Docker uses image `gradle` (same 8.14.3 pin).
RUN chmod +x gradlew && sed -i 's/\r$//' gradlew

USER gradle
# BuildKit cache: reuse dependency/wrapper caches across builds.
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --version --no-daemon \
 && gradle dependencies --no-daemon -q || true

COPY --chown=gradle:gradle src/ src/
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=build /home/gradle/project/build/libs/clearread-api.jar /app/clearread-api.jar
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=45s --retries=12 \
  CMD curl -sf http://127.0.0.1:8080/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/clearread-api.jar"]
