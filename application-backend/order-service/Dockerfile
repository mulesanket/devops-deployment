FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL org.opencontainers.image.title="shopease-order-service" \
      org.opencontainers.image.authors="Sanket Mule" \
      org.opencontainers.image.environment="development"

WORKDIR /app

RUN addgroup -g 10001 -S appgroup && adduser -u 10001 -S appuser -G appgroup

COPY --chown=appuser:appgroup order-service/target/order-service-*.jar /app/app.jar

USER appuser

EXPOSE 8083

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]
