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