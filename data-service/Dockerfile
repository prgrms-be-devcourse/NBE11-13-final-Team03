FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --chown=10001:10001 build/libs/app.jar app.jar
USER 10001:10001
EXPOSE 9092
ENTRYPOINT ["java", "-jar", "app.jar"]
