FROM postgres:16@sha256:78df81b1442dcc764c1104154da7162635e40cfffe67579c42a1c1b96dfc209c

RUN apt-get update && apt-get install -y --no-install-recommends \
    restic \
    && rm -rf /var/lib/apt/lists/*
