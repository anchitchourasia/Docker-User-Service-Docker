FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
# Download the specific, approved version from your Nexus
ADD http://localhost:8081/repository/maven-releases/com/heg/Docker-User-Service-Docker/1.0.5/Docker-User-Service-Docker-1.0.5.war app.war
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.war"]