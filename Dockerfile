# Dockerfile for eidasremotesigning service
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="wpanther"
LABEL description="eIDAS Remote Signing Service - CSC API v2.0 for XAdES/PAdES digital signatures"

# Install dependencies for SoftHSM (optional, for PKCS#11)
RUN apk add --no-cache \
    curl \
    && rm -rf /var/cache/apk/*

# Create app directory
WORKDIR /app

# Copy the built JAR file
COPY target/eidasremotesigning-*.jar app.jar

# Create keystore directory
RUN mkdir -p /app/keystores

# Expose the service port
EXPOSE 9000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
    CMD curl -sf http://localhost:9000/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

# JVM options for container environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
