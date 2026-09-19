# ---- build stage ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/*-SNAPSHOT.jar app.jar

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-jammy
# Fonts are needed to draw the shop name under the QR code (Java2D).
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core libfreetype6 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home navbat
USER navbat
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
