FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL org.opencontainers.image.title="shopease-auth-service" \
      org.opencontainers.image.authors="Sanket Mule" \
      org.opencontainers.image.environment="development"

WORKDIR /app

RUN addgroup -g 10001 -S appgroup && adduser -u 10001 -S appuser -G appgroup

COPY --chown=appuser:appgroup auth-service/target/auth-service-*.jar /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]