FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

ENV GRADLE_OPTS="-Xmx512m -Dorg.gradle.daemon=false -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1"
ENV JAVA_TOOL_OPTIONS="-Xmx512m"

COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src

RUN chmod +x ./gradlew
RUN ./gradlew bootJar --no-daemon -x test --no-parallel

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

ENV JAVA_TOOL_OPTIONS="-Xmx96m -XX:MaxMetaspaceSize=96m -XX:MaxDirectMemorySize=16m -XX:+UseSerialGC"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]