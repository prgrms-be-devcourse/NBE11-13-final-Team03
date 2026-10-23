FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --chown=10001:10001 build/libs/notification-service.jar app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
