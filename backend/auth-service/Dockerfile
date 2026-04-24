# ============================================
# Stage 1: Build
# ============================================
FROM maven:3.9-eclipse-temurin-21-alpine AS build

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
COPY auth-service/src auth-service/src

# 4. Build only the required module (and its dependency: shopease-common)
RUN mvn clean package -pl auth-service -am -DskipTests -B

# ============================================
# Stage 2: Runtime
# ============================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the built JAR
COPY --from=build /app/auth-service/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

# JVM tuning for containers
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
