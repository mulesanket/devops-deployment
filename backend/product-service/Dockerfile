# ============================================
# Stage 1: Build
# ============================================

FROM maven:3.9.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# 1. Copy parent pom + all module poms first (for dependency caching)
COPY pom.xml .
COPY shopease-common/pom.xml shopease-common/
COPY auth-service/pom.xml auth-service/
COPY product-service/pom.xml product-service/
COPY cart-service/pom.xml cart-service/
COPY order-service/pom.xml order-service/

# 2. Download dependencies (cached unless pom changes)
RUN mvn dependency:go-offline -B

# 3. Copy source code
COPY shopease-common/src shopease-common/src
COPY product-service/src product-service/src

# 4. Build only the required module (and its dependency: shopease-common)
RUN mvn clean package -pl product-service -am -DskipTests -B

# ============================================
# Stage 2: Runtime
# ============================================
FROM eclipse-temurin:21.0.5_11-jre-alpine AS runtime

LABEL maintainer="Sanket Mule"
LABEL application="shopease-product-service"
LABEL environment="development"

WORKDIR /app

# Copy the built JAR
COPY --from=build /app/auth-service/target/product-service-*.jar /app/app.jar

# Security: run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Set ownership
RUN chown -R appuser:appgroup /app

# Change user
USER appuser

# Document port where app is listening on
EXPOSE 8080

# JVM tuning for containers
ENTRYPOINT ["java",
  "-XX:MaxRAMPercentage=75.0",
  "-XX:InitialRAMPercentage=50.0",
  "-XX:+ExitOnOutOfMemoryError",
  "-jar", "/app/app.jar"]