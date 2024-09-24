FROM openjdk:17-jdk-slim

WORKDIR /app

COPY build/libs/order-service.jar /app/order-service.jar

ENTRYPOINT ["java", "-jar", "/app/order-service.jar"]

