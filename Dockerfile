# Build stage
FROM gradle:jdk21 AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
RUN chmod +x gradlew

# Cache only runtime dependencies layer
RUN ./gradlew dependencies --configuration runtimeClasspath --no-daemon

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# Extract layered JAR
RUN java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination build/extracted

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy layers in order of least-to-most frequently changed
COPY --from=build /app/build/extracted/dependencies ./
COPY --from=build /app/build/extracted/spring-boot-loader ./
COPY --from=build /app/build/extracted/snapshot-dependencies ./
COPY --from=build /app/build/extracted/application ./

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
