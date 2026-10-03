# syntax=docker/dockerfile:1
FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8 AS download
ARG TARGETARCH
# Version and hashes are reviewed together; no network-fetched checksum is trusted at build time.
RUN apk add --no-cache ca-certificates curl \
    && case "${TARGETARCH}" in \
         amd64) sha256=ac90da87a35641be5a0ac75d49de5161ddb47d629b5ba01261b0ae9e00aea15f ;; \
         arm64) sha256=61bb88e7435919834478ddd4d405a6de6d2c227079da5e8ee9655147398819a0 ;; \
         *) echo "Unsupported mc architecture: ${TARGETARCH}" >&2; exit 1 ;; \
       esac \
    && curl --fail --location --retry 3 \
         "https://github.com/minio/mc/releases/download/RELEASE.2025-04-16T18-13-26Z/mc.linux-${TARGETARCH}.RELEASE.2025-04-16T18-13-26Z" \
         --output /usr/local/bin/mc \
    && printf '%s  /usr/local/bin/mc\n' "${sha256}" | sha256sum -c - \
    && chmod 0755 /usr/local/bin/mc

FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8
LABEL org.opencontainers.image.source="https://github.com/minio/mc" \
      org.opencontainers.image.version="RELEASE.2025-04-16T18-13-26Z" \
      org.opencontainers.image.revision="b00526b153a31b36767991a4f5ce2cced435ee8e" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later"
COPY --from=download /usr/local/bin/mc /usr/local/bin/mc
COPY --from=download /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY LICENSE NOTICE /usr/share/licenses/minio-client/
ENTRYPOINT ["mc"]
