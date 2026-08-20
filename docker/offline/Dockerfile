FROM docker.io/library/offnav-android-builder:2026.08.20-amd64 AS build

WORKDIR /workspace
COPY . .

RUN test -s app/src/main/assets/tiles/region.mbtiles \
    && test -s app/src/main/assets/routing/region.ghz \
    && test -s app/src/main/assets/search/austin_places.db \
    && test -s offline/artifacts/austin-2026-08-20.offnav \
    && ./gradlew --offline --no-daemon clean \
        :app:assembleDebug \
        :app:testDebugUnitTest \
        :app:lintDebug \
    && mkdir -p /output \
    && cp app/build/outputs/apk/debug/app-debug.apk /output/offnav-debug.apk \
    && cp offline/artifacts/austin-2026-08-20.offnav /output/austin-2026-08-20.offnav \
    && cd /output \
    && sha256sum offnav-debug.apk austin-2026-08-20.offnav > SHA256SUMS

FROM scratch
COPY --from=build /output/ /
