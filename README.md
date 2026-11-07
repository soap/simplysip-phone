# SimplySIP Phone

An Android softphone for a multi-tenant cloud IP PBX (OpenSIPS + Asterisk + rtpengine), built with Kotlin, Jetpack Compose and [PJSIP](https://www.pjsip.org/) (PJSUA2).

> **Status: early development.** Voice calling works end to end against a lab PBX, but SIP credentials are still entered by hand on a debug screen. Login and automatic provisioning, push for incoming calls and TLS/SRTP are not done yet. Not ready for production use.

## Features

Working now:

- SIP registration over UDP, TCP or TLS, with automatic re-registration when the app returns to the foreground or the network changes (Wi-Fi ↔ mobile)
- Outgoing and incoming calls, G.711 (PCMU/PCMA), RFC 4733 DTMF
- Mute, hold (re-INVITE), remote hold, early media and ringback
- Audio routes: earpiece, speaker, wired headset, Bluetooth (Android 12+); proximity sensor turns the screen off on the earpiece
- Ongoing-call notification (foreground service) with a hang-up action
- One call at a time: a second incoming call is answered `486 Busy Here`
- Thai and English UI

Planned:

| Phase | Scope |
|---|---|
| 3 | Login against the PBX management API, automatic SIP provisioning, call history |
| 4 | Incoming calls in the background: FCM push (RFC 8599) and Core-Telecom |
| 5 | Transfer, contacts, device management, Bluetooth on older Android versions |
| 6 | Device and network QA, TLS/SRTP by default, Play Store release |

The full plan is in [docs/development-plan.md](docs/development-plan.md) (Thai).

## Architecture

```
:app               Compose UI, CallController, audio routing, foreground service
:core              coroutine dispatchers, KeystoreCipher (AES-256-GCM in Android Keystore)
:sip:engine        SipEngine interface, PjsipEngine, FakeSipEngine, CallStateMachine
:sip:pjsip-native  prebuilt libpjsua2.so + SWIG Java bindings (built by a script, not committed)
```

- The UI never calls PJSUA2 directly. Everything goes through the `SipEngine` interface, which exposes events as a `Flow<SipEvent>`. `FakeSipEngine` makes the UI testable without native code.
- PJSIP runs with no worker threads (`threadCnt = 0`, `mainThreadOnly`). All PJSUA2 calls happen on one dedicated thread that also polls `libHandleEvents`, and native objects are deleted explicitly instead of relying on the GC.
- Call state is a sealed `CallState` driven by a pure reducer (`CallReducer`), so duplicate, late or out-of-order SIP events are harmless.
- `CallController` is the single place that changes calls. It applies side effects from the current state: ringtone, ringback, audio session and foreground service.

## Requirements

- Android Studio with JDK 17, Android SDK 37
- A device or emulator running Android 8.0 (API 26) or later, `arm64-v8a` or `x86_64`
- Linux or WSL to build PJSIP once, with `make`, `gcc/g++`, `perl`, `python3` and `curl`. The script downloads the NDK and SWIG itself and needs no root access.

## Build

1. Build the PJSIP binaries (once, or after the pinned versions change):

   ```bash
   sip/pjsip-native/scripts/build-pjsip.sh                 # arm64-v8a + x86_64
   ABIS="arm64-v8a" sip/pjsip-native/scripts/build-pjsip.sh # one ABI only
   ```

   Versions are pinned in the script and recorded in [sip/pjsip-native/BUILD_INFO](sip/pjsip-native/BUILD_INFO): PJSIP 2.17, OpenSSL 3.5.9 (static), NDK r28c, SWIG 4.5.1. The script checks that every `.so` is 16 KB page aligned. Downloads and intermediate files go to `~/.cache/simplysip-pjsip` (override with `WORK=`).

   On Windows, run the script from WSL inside the repository folder. The Gradle build stops with a clear message if the binaries are missing.

2. Build, test and lint:

   ```bash
   ./gradlew assembleDebug testDebugUnitTest lintDebug
   ./gradlew :sip:engine:connectedDebugAndroidTest   # PJSIP smoke tests on a device or emulator
   ```

CI ([.github/workflows/android.yml](.github/workflows/android.yml)) runs the same steps plus `spotlessCheck`, and caches the PJSIP build keyed on the build script.

## Development

### Formatting

Kotlin and Gradle Kotlin DSL files are formatted with [ktlint](https://pinterest.github.io/ktlint/) through [Spotless](https://github.com/diffplug/spotless). The rules live in [.editorconfig](.editorconfig): Android Studio style, 140 columns, trailing commas. Android Studio picks up the same settings.

```bash
./gradlew spotlessCheck   # verify (CI and the pre-commit hook)
./gradlew spotlessApply   # fix
```

### Git hooks

Enable them once per clone:

```bash
git config core.hooksPath .githooks
```

| Hook | Runs |
|---|---|
| `pre-commit` | `spotlessCheck` |
| `commit-msg` | [Seven rules](https://cbea.ms/git-commit/): subject ≤ 50 characters, capitalised, no trailing period, blank second line, body lines ≤ 72 |
| `pre-push` | `spotlessCheck assembleDebug testDebugUnitTest lintDebug` (needs the PJSIP binaries) |

Skip a hook in an emergency with `--no-verify`. CI still runs the same checks.

## Trying it

Until provisioning lands (Phase 3), debug builds have a SIP test screen:

1. Install a debug build (application ID `com.simplysip.phone.debug`).
2. Tap the account icon on the dialer.
3. Enter your SIP domain, username, password, transport and port, then tap **Register**.

After a successful registration the account is remembered, with the password encrypted by the Android Keystore, and it registers again on every start. **Forget saved** removes it.

Notes for OpenSIPS-based servers:

- Leave **Outbound proxy** empty unless your server expects one. Without it the app puts the transport in the request URI (`sip:<domain>;transport=tcp`) and sends no `Route` header. Scripts that reject preloaded routes answer `403 Preload Route denied`.
- Voice activity detection is off so RTP keeps flowing through media relays and NAT.
- To test audio, call an echo service if your PBX has one.

The emulator needs host audio for calls to be audible. Don't start it with `-no-audio`.

## Security

- Secrets are stored only with Android Keystore-backed AES-GCM, and credentials and tokens are never logged.
- Until TLS is enabled on your server, SIP over UDP/TCP sends credentials and media unencrypted. Use TLS (port 5061) with a publicly trusted certificate when your server supports it. The app validates server certificates against the Android system CAs.

Please report security issues privately to the maintainer rather than opening a public issue.

## Third-party software

Building this app includes:

- [PJSIP](https://www.pjsip.org/), dual-licensed under GPL v2 or later or a commercial license from Teluu
- [OpenSSL](https://www.openssl.org/), Apache License 2.0

Any binary you distribute must meet the terms of the license you use PJSIP under.

## License

No license has been chosen yet. Until one is added, all rights are reserved by the author.
