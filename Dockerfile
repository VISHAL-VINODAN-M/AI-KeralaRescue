FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew ./gradlew
COPY gradle ./gradle
COPY backend ./backend
RUN sed -i 's/\r$//' gradlew && chmod +x ./gradlew && ./gradlew -p backend bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /var/lib/kerala-rescue && chown -R 10001:10001 /var/lib/kerala-rescue /app
COPY --from=build --chown=10001:10001 /workspace/backend/build/libs/kerala-rescue-backend-0.1.0.jar /app/app.jar
ENV SPRING_DATASOURCE_URL="jdbc:h2:file:/var/lib/kerala-rescue/kerala-rescue;DB_CLOSE_ON_EXIT=FALSE"
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:InitialRAMPercentage=25.0"
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
