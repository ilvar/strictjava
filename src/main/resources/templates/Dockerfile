# CI builds the application distribution once and copies it into from-artifact.
# A plain docker build uses from-source and builds with the pinned Gradle wrapper.

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
ENTRYPOINT ["/app/bin/__PROJECT__"]

FROM runtime AS from-artifact
COPY build/install/__PROJECT__/ /app/

FROM eclipse-temurin:25-jdk AS builder
WORKDIR /src
COPY gradle gradle
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties gradle.lockfile ./
RUN chmod +x gradlew && ./gradlew --no-daemon help
COPY src src
RUN ./gradlew --no-daemon installDist

FROM runtime AS from-source
COPY --from=builder /src/build/install/__PROJECT__/ /app/
