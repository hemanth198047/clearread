FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/build/libs/clearread-api.jar /app/clearread-api.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/clearread-api.jar"]
