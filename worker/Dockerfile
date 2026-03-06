FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    libraw-tools=0.21.4-r2 \
    vips-tools=8.17.3-r1 \
    perl=5.42.0-r0 \
    perl-image-exiftool=13.36-r0 \
    tini=0.19.0-r3

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# Non-root user
RUN addgroup -S worker && adduser -S worker -G worker
USER worker

WORKDIR /app
COPY build/libs/app.jar app.jar
ENTRYPOINT ["/sbin/tini", "--", "sh", "-c", "java $JAVA_OPTS -jar app.jar"]
