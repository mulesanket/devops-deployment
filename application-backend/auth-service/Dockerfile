# ============================================
# Stage 1: Build
# ============================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

COPY pom.xml .
COPY shopease-common/pom.xml shopease-common/
COPY auth-service/pom.xml auth-service/
COPY product-service/pom.xml product-service/
COPY cart-service/pom.xml cart-service/
COPY order-service/pom.xml order-service/

RUN mvn dependency:go-offline -B

COPY shopease-common/src shopease-common/src
COPY auth-service/src auth-service/src

RUN mvn clean package -pl auth-service -am -DskipTests -B


# ============================================
# Stage 2: Runtime
# ============================================
FROM eclipse-temurin:21.0.5_11-jre-alpine AS runtime

LABEL org.opencontainers.image.title="shopease-auth-service" \
      org.opencontainers.image.authors="Sanket Mule" \
      org.opencontainers.image.environment="development"

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build --chown=appuser:appgroup /app/auth-service/target/auth-service-*.jar /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]