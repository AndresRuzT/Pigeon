# Multi-stage Dockerfile for Pigeon
FROM maven:3.9.8-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S pigeon && adduser -S pigeon -G pigeon
USER pigeon:pigeon
COPY --from=builder --chown=pigeon:pigeon /workspace/target/*.jar app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
