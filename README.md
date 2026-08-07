# MacLink Companion for Android

Native Android companion application for connecting an Android phone to a Mac
over the local network.

## Technology

- Kotlin
- Jetpack Compose
- Coroutines and Flow
- Android Network Service Discovery
- OkHttp WebSocket
- Android Keystore

## Development status

The project has a native application shell, discovers `_maclink._tcp` Bonjour
services through Android NSD, and can explicitly connect to a selected Mac for a
bounded pre-pairing presence handshake. It now scans the Mac's short-lived QR
code without requesting camera permission, keeps its P-256 signing identity in
Android Keystore, verifies the pairing transcript, and stores approved Mac
public identities. It handles Android 17's local-network permission and retains
a compatibility path for older supported Android versions. Encrypted session
transport and feature synchronization are not implemented yet.

The shared system design is maintained in the parent `MacLink` directory:
`ARCHITECTURE.md`, `PROTOCOL.md`, and `SECURITY.md`.

## Requirements

- Android Studio 2026.1 or newer
- JDK 21
- Android SDK configured by the project

## Build

```sh
./gradlew assembleDebug
```
