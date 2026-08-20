# Offline Docker build

The offline package contains a Docker-compatible builder image archive and the
finished Austin runtime data. It does not download dependencies or generate map
data during the Docker build.

Requirements:

- Docker or Podman on Linux amd64;
- at least 6 GB of available memory; and
- at least 6 GB of free disk space in the container image store.

From the repository root, with networking disabled:

```bash
cd offline/artifacts
sha256sum --check SHA256SUMS
cd ../..
docker load --input offline/artifacts/offnav-android-builder-2026.08.20-amd64.tar
docker build --network=none --pull=false \
  --file docker/offline/Dockerfile \
  --tag offnav-offline-output:2026.08.20 .
```

The output image is intentionally `scratch`-based and contains only the APK,
the importable Austin bundle, and their checksums. Extract them with:

```bash
container_id=$(docker create offnav-offline-output:2026.08.20)
docker cp "$container_id":/offnav-debug.apk ./offnav-debug.apk
docker cp "$container_id":/austin-2026-08-20.offnav ./austin-2026-08-20.offnav
docker cp "$container_id":/SHA256SUMS ./SHA256SUMS
docker rm "$container_id"
sha256sum --check SHA256SUMS
```

Podman accepts the same commands with `podman` in place of `docker`.

The builder archive contains the pinned Java 21 runtime, Android SDK Platform
36.1, Android Build-Tools 36.0.0, Gradle 9.5, and the exact dependency cache
needed by this project. The Docker build itself runs Gradle with `--offline`
and fails if any dependency is missing.
