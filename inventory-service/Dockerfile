FROM openjdk:17-jdk-slim

WORKDIR /app

COPY build/libs/inventory-service.jar /app/inventory-service.jar

# 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "/app/inventory-service.jar"]