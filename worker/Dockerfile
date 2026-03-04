FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    libraw-utils \
    vips-tools \
    perl perl-image-exiftool \
    tini

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# Non-root user
RUN addgroup -S worker && adduser -S worker -G worker
USER worker

WORKDIR /app
COPY build/libs/app.jar app.jar
ENTRYPOINT ["/sbin/tini", "--", "sh", "-c", "java $JAVA_OPTS -jar app.jar"]
