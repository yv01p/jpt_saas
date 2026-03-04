FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appuser && adduser -S appuser -G appuser

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

COPY build/libs/app.jar app.jar
EXPOSE 8080

USER appuser
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
