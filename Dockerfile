FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY app.war app.war
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.war"]