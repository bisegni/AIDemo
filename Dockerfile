FROM eclipse-temurin:21-alpine

# Copy application files
COPY . /app
WORKDIR /app

# Expose the application port (replace 8081 with the static port number from `./src/main/resources/application.yaml`)
EXPOSE 8081

# Set the entry point for the application
ENTRYPOINT ["./gradlew", "bootRun"]
