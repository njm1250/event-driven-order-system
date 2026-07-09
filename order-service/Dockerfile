FROM gradle:8.10-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle :order-service:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/order-service/build/libs/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
