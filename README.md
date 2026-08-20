# OffNav

OffNav is an offline Android navigation app built with Kotlin, Jetpack Compose,
MapLibre, GraphHopper, Room, and Gradle.

## Workstation setup

Install Android Studio with:

- a Java 21 JDK (Android Studio's bundled JDK works);
- Android SDK Platform 36.1;
- Android SDK Build-Tools 36.0.0; and
- Android SDK Platform-Tools.

Point `local.properties` at the SDK on the current machine. For example:

```properties
sdk.dir=/home/your-user/Android/Sdk
```

The Gradle wrapper downloads the pinned Gradle version and resolves the pinned
Java 21 daemon toolchain. A separate system `gradle` or `javac` is not required.

## Build and test

From the repository root:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For device iteration, enable USB debugging on a device running Android 11
(API 30) or newer, then use:

```bash
adb devices
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
```

Debug builds use the `com.example.offnav.debug` application ID and the
`OffNav Dev` label. They can coexist with a release build and do not depend on
the debug signing key from another workstation. Run the device test before
installing a working copy: the Android test runner manages and may uninstall
the debug package, clearing that package's local test data.

## Offline Austin data

The distributable project package includes a finished Austin map, routing
graph, and search database under `app/src/main/assets`. They are copied into
the APK unchanged, so a fresh install can map, search, and route without a
network connection or a separate import step.

The matching importable bundle is
`offline/artifacts/austin-2026-08-20.offnav`. The large binary files are ignored
by Git and must remain in any source archive supplied to an offline builder.
Their provenance and OpenStreetMap attribution are recorded in
`app/src/main/assets/OFFLINE_DATA_NOTICE.txt`.

## Fully offline Docker build

The offline distribution also includes a Docker-compatible builder image with
Java, the Android SDK, Gradle, and all resolved dependencies. Follow
[`docker/offline/README.md`](docker/offline/README.md) to load the image and
build the APK with container networking disabled.

## Release signing

Release signing is optional for local debug work. To produce a signed release,
provide the ignored `offnav-release.jks` and `keystore.properties` files. The
properties file must define `storeFile`, `storePassword`, `keyAlias`, and
`keyPassword`.
