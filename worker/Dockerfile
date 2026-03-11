FROM eclipse-temurin:21-jre-alpine@sha256:6ad8ed080d9be96b61438ec3ce99388e294af216ed57356000c06070e85c5d5d

RUN apk add --no-cache \
    libraw-tools=0.21.4-r2 \
    vips-tools=8.17.3-r1 \
    perl=5.42.0-r0 \
    perl-image-exiftool=13.36-r0 \
    tini=0.19.0-r3

# Non-root user
RUN addgroup -S worker && adduser -S worker -G worker
USER worker

WORKDIR /app
COPY build/libs/app.jar app.jar
ENTRYPOINT ["/sbin/tini", "--", "java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
