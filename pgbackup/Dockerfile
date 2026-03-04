FROM postgres:16

RUN apt-get update && apt-get install -y --no-install-recommends \
    restic \
    && rm -rf /var/lib/apt/lists/*
