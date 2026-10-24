# Deck Satellite for Android — prototype 0.2.0

Native Kotlin USB-to-Companion bridge for a physical Stream Deck attached to an Android tablet. Initially targeted at Samsung Galaxy Tab S11. Independent project, not an official Bitfocus or Elgato app.

## What's changed in 0.2.0

The 0.1.0 APK only detected XL devices. This update adds the normal **15-key (5 columns × 3 rows)** Stream Deck, automatically selects the USB protocol, uses 72 × 72 images, registers a 15-key Companion surface, and changes the tablet's indicator grid to match. XL support remains available with 96 × 96 images and an 8 × 4 layout.

The application is now labelled **Deck Satellite**. It keeps the same Android package and signing key so it can update the earlier **XL Satellite** installation without uninstalling it or losing saved connection settings.

## Supported USB models

All use Elgato vendor ID 0x0FD9. One deck can be active at a time.

| Model | Product ID | Layout | Image transport |
|---|---|---|---|
| Original Stream Deck (2017) | 0x0060 | 5 × 3 | Legacy 72 × 72 BMP, two 8191-byte reports; mirrored hardware key ordering |
| Stream Deck 2019 | 0x006D | 5 × 3 | 72 × 72 JPEG |
| Stream Deck MK.2 | 0x0080 | 5 × 3 | 72 × 72 JPEG |
| Stream Deck MK.2 Scissor | 0x00A5 | 5 × 3 | 72 × 72 JPEG |
| Stream Deck XL | 0x006C | 8 × 4 | 96 × 96 JPEG |
| Stream Deck XL 2022 | 0x008F | 8 × 4 | 96 × 96 JPEG |

These models have implementation and automated protocol coverage. Physical hardware compatibility still needs validation on the user's devices. Mini, Neo, Stream Deck +, + XL and modules are not included.

## Install and test

1. Tap **Stop everything** in the earlier app, if running.
2. Transfer **deck-satellite-0.2.0-debug.apk** to the Tab S11 and open it. Install as an update. Allow installation from the file-opening app if Android requests it.
3. Connect the Stream Deck using a USB data cable or powered hub.
4. Open **Deck Satellite**, tap **Start USB**, and approve Android's USB permission dialog.
5. Confirm the status names your detected model and the tablet displays a **5 × 3** grid. The deck should display **1–15**, left to right, top to bottom. A cyan marker belongs at each image's top-left corner.
6. Press and release every key, including the bottom-right key. Confirm the corresponding tablet indicator and deck image change. Test simultaneous presses and the brightness slider.
7. Enter the Companion computer's reachable IP/hostname and Satellite TCP port (normally **16622**), then tap **Connect**. Enable the Satellite API in Companion and make sure the port is reachable from the tablet.
8. Find the new Android Stream Deck surface in Companion and configure its page/group. Check images, a harmless test action, press/release behavior, page changes and feedback updates.
9. Check USB unplug/replug, network recovery and operation with the tablet screen off. After replugging, tap **Start USB** if Android needs USB permission again.
10. Use **Share log** if a step fails. Logs now include connected USB vendor/product IDs as well as model/serial and connection status. Record the failed step, cable/hub and Companion version.

Use **USB test mode** to disconnect Companion and return to the numbered pattern. Use **Stop everything** or the notification's **Stop** action to stop the service and release locks.

## Behavior

- USB permission prompt, HID interface discovery, physical button press/release events and brightness.
- Foreground service and CPU/Wi-Fi locks while running; no root required.
- Companion TCP surface registration with a stable ID based on the deck serial.
- Raw RGB bitmap reception, model-specific encoding and orientation, latest-image-per-key queue.
- Heartbeats, network retry/backoff, USB recovery while permission remains granted.
- Manual connection configuration; no discovery, WebSocket, TLS, authentication, Cloud or automatic boot start.
- Held buttons are not replayed after reconnect; press again once reconnected.
- Existing XL surface identifiers are preserved. A 15-key deck registers separately from the XL.
- Intended for a trusted LAN or VPN. No firewall or battery settings are changed automatically.

Start with non-production Companion buttons. Power stability, Android background behavior and actual USB/Companion interoperability remain hardware-test requirements.

## Build

JDK 17, Android SDK platform 35 and build-tools 35.0.0. Minimum Android 10 (API 29), target API 35. No NDK.

Open the project in Android Studio, or set JAVA_HOME/ANDROID_HOME and run:

~~~sh
./gradlew assembleDebug testDebugUnitTest lintDebug
~~~

Windows: gradlew.bat. Output: app/build/outputs/apk/debug/app-debug.apk.

Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20. JUnit is test-only. Debug APKs are for sideloaded testing; public releases require your own signing process.

## Source and protocol references

- DeckModel.kt: product identification, geometry, legacy hardware key mapping.
- Protocol.kt: modern/legacy HID packets, original BMP encoding, Satellite parser.
- UsbDeck.kt: USB request lifecycle and bounded image mailbox.
- DeckImages.kt: test patterns, RGB decoding and model-specific image encoding.
- SatelliteClient.kt: Companion registration, events, heartbeats and reconnect.
- BridgeService.kt/MainActivity.kt: Android lifecycle, permission flow, adaptive grid and diagnostics.

[Companion Satellite API](https://companion.free/for-developers/Satellite-API/)

[Elgato 15-key HID protocol](https://docs.elgato.com/streamdeck/hid/stream-deck-classic/)

[Elgato XL HID protocol](https://docs.elgato.com/streamdeck/hid/stream-deck-xl/)

Original 2017 protocol behavior was adapted with reference to [node-elgato-stream-deck](https://github.com/Julusian/node-elgato-stream-deck). Its MIT notice is included in THIRD-PARTY-NOTICES.txt and the APK assets.
