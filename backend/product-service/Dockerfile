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