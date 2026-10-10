# ClearRead API — multi-stage Gradle build
# Exit 127 on ./gradlew is almost always a missing +x bit or CRLF after COPY from Windows.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

RUN apk add --no-cache bash ca-certificates

# Dependency layer first (better cache)
COPY gradle/wrapper/ gradle/wrapper/
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew \
  && sed -i 's/\r$//' gradlew \
  && ./gradlew --version --no-daemon

COPY src/ src/
RUN chmod +x gradlew \
  && ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=build /workspace/build/libs/clearread-api.jar /app/clearread-api.jar
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=45s --retries=12 \
  CMD curl -sf http://127.0.0.1:8080/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/clearread-api.jar"]
