# syntax=docker/dockerfile:1.4
# Stage 1: Build Java backend application
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /workspace

# Copy backend pom and resolve dependencies
COPY flyo-rescue-demo/backend/pom.xml .
RUN mvn dependency:go-offline -B

# Copy backend source and compile package
COPY flyo-rescue-demo/backend/src src
RUN mvn clean package -DskipTests -B

# Stage 2: Production Minimal Alpine Runtime
FROM eclipse-temurin:17-jre-alpine AS runner
WORKDIR /app

# Install Node.js for Playwright rebook worker automation
RUN apk add --no-cache nodejs npm curl

# Create non-root system user
RUN addgroup -S flyogroup && adduser -S flyouser -G flyogroup

# Copy built artifact from builder stage
COPY --from=builder /workspace/target/rescue-backend-*.jar /app/app.jar

# Copy agent Playwright worker assets
COPY flyo-rescue-demo/agent /app/agent
RUN cd /app/agent && npm install --production 2>/dev/null || true

# Set ownership
RUN chown -R flyouser:flyogroup /app

USER flyouser:flyogroup

ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
