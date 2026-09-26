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
a compatibility path for older supported Android versions. Paired peers establish
authenticated encrypted sessions. Recovery includes jittered retries, refreshed
discovery endpoints, default-network change handling, and 20-second connection
and authentication deadlines. A ViewModel retains the connection across screen
rotation; discovery resumes when the Activity returns to the foreground.

Background-service ownership, process-death restoration, snapshot reconciliation,
and feature synchronization remain pending. These builds are development-only;
the custom session protocol still requires independent security review.

## Recovery verification

After pairing, keep discovery running and verify Wi-Fi off/on recovery. Rotate
the phone and confirm the connected session is retained. Open the QR scanner
and return to confirm discovery resumes. Explicit Disconnect must prevent retries.
Physical Pixel testing is still required for these lifecycle/network changes.

Network callbacks wait for link properties before triggering recovery, following
[Android's network-state guidance](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).
Connection ownership follows the
[ViewModel lifecycle](https://developer.android.com/topic/libraries/architecture/viewmodel).

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
