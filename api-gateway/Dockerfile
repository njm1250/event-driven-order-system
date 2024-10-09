FROM openjdk:17-jdk-slim

WORKDIR /app

COPY build/libs/api-gateway.jar /app/api-gateway.jar

ENTRYPOINT ["java", "-jar", "/app/api-gateway.jar"]