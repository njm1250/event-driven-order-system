FROM openjdk:17-jdk-slim

WORKDIR /app

COPY build/libs/payment-service.jar /app/payment-service.jar

# 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "/app/payment-service.jar"]