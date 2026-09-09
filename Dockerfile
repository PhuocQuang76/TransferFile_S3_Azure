# Stage 1: Build the artifact
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN chmod +x mvnw
RUN ./mvnw clean package -DskipTests

# Stage 2: Run the application
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create a non-root group and user, and set up the logs directory with proper permissions
RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && mkdir -p /app/logs \
    && chown -R appuser:appgroup /app

USER appuser

COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8586

ENTRYPOINT ["java", "-jar", "app.jar"]