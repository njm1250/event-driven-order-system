FROM gradle:8.10-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle :inventory-service:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/inventory-service/build/libs/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
