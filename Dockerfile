FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY . .
RUN ./gradlew build --offline 2>/dev/null || gradle build
CMD ["gradle", "run"]
