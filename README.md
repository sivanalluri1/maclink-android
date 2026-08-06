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

The project is in its foundation phase. The first milestone establishes a native
application shell and the boundaries for pairing, connectivity, protocol,
security, synchronization, and phone capabilities.

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

