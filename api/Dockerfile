FROM eclipse-temurin:21-jre-alpine@sha256:6ad8ed080d9be96b61438ec3ce99388e294af216ed57356000c06070e85c5d5d

RUN apk add --no-cache tini=0.19.0-r3

WORKDIR /app

RUN addgroup -S appuser && adduser -S appuser -G appuser

COPY build/libs/app.jar app.jar
EXPOSE 8080

USER appuser
ENTRYPOINT ["/sbin/tini", "--", "java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC", "-jar", "app.jar"]
