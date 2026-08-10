FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

ENV JAVA_TOOL_OPTIONS="-Xmx128m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
