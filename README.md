# Pocket Wake

One-tap Wake-on-LAN for your PCs, from anywhere, through the MQTT bridge of a
[Pocket AdBlock](https://github.com/joanrra2/pocket-adblock) board.

Pick a PC, tap **Wake**, and watch it come up: the board sends the magic packet on your
home network, checks that the PC answers, and the app shows **Online after 23 s**.

<p>
  <img src="docs/screenshots/home.png" width="270" alt="Home screen: connection status, board online, four PCs with wake progress and results">
  <img src="docs/screenshots/home-dark.png" width="270" alt="Home screen in dark mode">
  <img src="docs/screenshots/settings.png" width="270" alt="Broker settings with a successful connection test">
</p>

## What it shows

- **Connection card**: connected / reconnecting / why it can't connect (wrong password,
  untrusted certificate, wrong port...), whether the **board** is online (its MQTT last
  will), the transport (TLS, port), the topic prefix and when the last update arrived.
- **One card per PC** with a green / grey dot. PCs that have an IP on the board appear by
  themselves; PCs without an IP can be added by name (case-sensitive, e.g. `OFFICE-PC`).
- **Wake progress**: "Waking… 12 s" with a 3-minute progress bar, then
  "Online after N s", "Already online", "Magic packet sent" (PCs the board can't check),
  or a hint when nothing answers after 3 minutes.
- **Last wake** per PC ("Last woken yesterday · online after 41 s"), kept on the phone.
- **Test connection** in settings checks the login and TLS, whether the board is online
  and lists the PCs it reports, before you save.

## Privacy and permissions

- Only the `INTERNET` permission. No analytics, no ads, no trackers, no background
  service: the app connects only while it is open and disconnects when you leave it.
- The broker password is encrypted (AES-256-GCM) with a key held in the Android Keystore.
  App backups are disabled.
- TLS is verified against Android's trusted certificate authorities with hostname
  checking. There is no "accept any certificate" option.

## Setup

1. **On the board** (web UI, Wake-on-LAN / MQTT): turn on the MQTT bridge, note the
   **topic prefix** (default `sinkhole`) and give each PC a name. Give PCs an IP if you
   want live online/offline dots.
2. **On your broker** create a **separate login for the phone** (don't reuse the board's).
   It needs:
   - publish: `<prefix>/wol/+/set`
   - subscribe: `<prefix>/wol/+/state`, `<prefix>/status`
3. **In the app**: tap **Set up broker** and enter
   - Address: the broker host only, e.g. `abc123.ala.eu-central-1.emqxsl.com`
     (no `mqtts://`)
   - Port **8883** with **TLS** on (EMQX Cloud Serverless "MQTT over TLS")
   - Username and password of the phone login
   - Topic prefix, same as on the board

   Tap **Test connection**: you should see *Connected*, *Board online* and your PCs.
   Then **Save**.
4. Tap **Wake** on a PC.

### Topics used

| Topic | Direction | Payload |
|---|---|---|
| `<prefix>/wol/<name>/set` | app → board | `1` (any payload wakes `<name>`; QoS 1, not retained) |
| `<prefix>/wol/<name>/state` | board → app | `online` / `offline` (retained; only PCs with an IP) |
| `<prefix>/status` | board → app | `online` / `offline` (retained, last will) |

### Troubleshooting

| Message | What to check |
|---|---|
| Wrong username or password | The phone login on the broker. |
| Login refused | The broker's access rules (ACL) for the phone login. |
| The broker closed the connection. Is port … a TLS port? | Use 8883 with TLS on (or 1883 with TLS off on brokers that allow it). |
| Certificate isn't trusted / doesn't match | Use the broker's host name, not an IP address. |
| No board status under `<prefix>/status` | The prefix differs from the board's, or the board's MQTT bridge is off. |
| No answer after 3 min | Wake-on-LAN in the PC's BIOS/UEFI and network adapter, wired Ethernet, PC on mains power. |

## Install

- **Debug builds**: every push builds `pocket-wake-debug-<n>`, under *Actions → Build →
  Artifacts* (unzip it to get the APK).
- **Releases**: tags `v*` publish a signed APK on the *Releases* page.

Allow "Install unknown apps" for your browser or file manager when Android asks.
Debug and release builds install side by side (the debug one is "Pocket Wake (debug)").

## Build

Requirements: JDK 17+ (21 recommended) and the Android SDK (platform 37).

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. To install it on a phone with
USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Other tasks:

```bash
# End-to-end MQTT test against the public test broker broker.emqx.io (random topics, cleaned up afterwards)
POCKETWAKE_IT=1 ./gradlew testDebugUnitTest --tests '*BrokerIntegrationTest*'

# Re-render the README screenshots (docs/screenshots) with Robolectric
./gradlew recordRoborazziDebug

# Release APK (unsigned unless the signing variables below are set)
./gradlew assembleRelease
```

### Release signing

Signing keys are never stored in the repository. The release build reads them from
environment variables, which the GitHub workflow fills from repository secrets:

| Secret | Value |
|---|---|
| `POCKETWAKE_KEYSTORE_BASE64` | your upload/release keystore (`.jks`), base64-encoded |
| `POCKETWAKE_KEYSTORE_PASSWORD` | keystore password |
| `POCKETWAKE_KEY_ALIAS` | key alias |
| `POCKETWAKE_KEY_PASSWORD` | key password |

Create the keystore yourself (for example with `keytool -genkeypair -v -keystore
release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias pocketwake`), keep a backup
somewhere safe (losing it means users must uninstall to update), and add the secrets
under *Settings → Secrets and variables → Actions*. Base64 for the first secret:
`base64 -w0 release.jks` (Linux) or
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))` (PowerShell).

Then push a tag:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

To sign locally instead, set `POCKETWAKE_KEYSTORE_FILE` (a path) and the three
password/alias variables before `./gradlew assembleRelease`.

## How it works

- Kotlin, Jetpack Compose (Material 3, dynamic colour on Android 12+), minSdk 26.
- MQTT 3.1.1 with the [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client)
  (Apache-2.0); reconnects with backoff (1 → 30 s) on network errors, stops on login or
  certificate errors.
- The connection follows the app's foreground state (`ProcessLifecycleOwner`), so rotating
  the phone doesn't reconnect.
- Code layout: `core/` pure Kotlin (topics, the host and wake state machine, formatting;
  unit-tested), `mqtt/` broker session and connection test, `data/` settings and the
  Keystore-encrypted password, `ui/` Compose screens.

See [ROADMAP.md](ROADMAP.md) for what's planned next.
