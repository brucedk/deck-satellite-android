# Validation — version 0.2.0 — 2026-10-24

- assembleDebug: PASS.
- testDebugUnitTest: PASS, **17 tests**, zero failures/errors/skips.
- lintDebug: PASS, zero errors, four previously reviewed warnings.
- apksigner verification: PASS, APK v2 signature, one debug signer.
- Update compatibility: package io.github.androidsatellite unchanged, versionCode increased from 1 to 2, certificate SHA-256 matches the 0.1.0 APK: ec3cad658e33a4d16b9ec8d4c6768be100235d2e9d31f847cd289aa090130f94.
- APK version 0.2.0, label Deck Satellite, min SDK 29, target/compile SDK 35.
- APK SHA-256: 0e30bdf8bb9c72d6dee6b7079268b6cda6a5b1104c12b999e664cb40eaa98b63

## Regression coverage

Seven base protocol tests cover packet chunking/reconstruction, malformed input, brightness, quoted fields, coordinate bounds, bounded line parsing and supported Satellite API versions.

Seven model tests cover USB model IDs, 15-key modern input/output, legacy mirrored key order, BMP header/pixel orientation, original two-packet framing, distinct brightness/serial commands and 5-column/72px Companion registration.

Three localhost mock-Companion tests cover XL registration/input/image routing, a complete 15-key session including bottom-right key input and image routing, and network reconnection.

## Lint warnings

The four warnings remain: Android 12+ data extraction rules not explicitly configured (cloud backup disabled), deliberate foreground-service wake lock without timeout, and two English-only UI formatting warnings. They are unchanged from the original prototype.

## Hardware boundary

ADB reported no attached devices. Neither this APK nor the earlier APK has been installed or exercised on a tablet from this development environment. Actual USB behavior, permissions, image orientation, button mapping, power/hub stability, real Companion integration and screen-off reliability require the user's hardware test. Automated tests and signature verification do not prove physical compatibility.

## Build environment

JDK 17, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20, Android platform 35 and build-tools 35.0.0. Tools and caches remain workspace-local. The prior environment-only Java Unix-domain socket fallback was reused; it is not part of the Android app.
