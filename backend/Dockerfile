FROM eclipse-temurin:25-jre

WORKDIR /app
ARG JAR_FILE=build/libs/backend-application.jar
ARG APP_ROLE=api
COPY --chown=10001:10001 ${JAR_FILE} app.jar
ENV APP_ROLE=${APP_ROLE}
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
