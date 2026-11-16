# Roadmap

Current status: **beta** (0.1.x), as an Android app and a web app (iPhone, iPad, any
browser). The MVP covers one board, one-tap wake with confirmation, and setup with a connection
test, plus QR setup from the board (Android scans it; on iPhone the camera opens the web
app with the settings filled in). Below are the use cases the code is already shaped for, roughly in the order they
make sense. Items marked **(firmware)** also need a change in the Pocket AdBlock
firmware.

## Phase 2: faster wake

- **Home-screen widget**: one button per favourite PC with its online dot. Tapping
  connects briefly, sends the wake, and shows the result on the widget.
- **Quick Settings tile**: "Wake OFFICE-PC" from the notification shade. Long-press opens
  the app.
- **Launcher shortcuts**: long-press the app icon for "Wake <PC>" (dynamic shortcuts
  for the last-woken PCs, and pinned shortcuts).

All of these connect only for the few seconds a wake takes; still no permanent
background service. Widgets and tiles are Android-only; on iPhone the web app's home-screen
icon is the closest equivalent (an iOS Shortcut that opens the page is another option).

## More PCs, more control

- **Favourites and order**: pin PCs to the top, drag to reorder, hide PCs you never wake.
- **Display names**: show "Office PC" while keeping the case-sensitive topic name
  `OFFICE-PC`.
- **Groups / Wake all**: "Wake office" sends to several PCs at once and tracks each one.
- **Wake history**: a per-PC log of wakes with time-to-online, to spot a PC that stopped
  answering.
- **Schedules** (optional, explicit opt-in): "wake OFFICE-PC on weekdays at 8:00" through
  Android's exact alarms. This would be the only feature that runs in the background, and
  it stays off unless you turn it on.

## More boards and brokers

- **Several boards** (home, office, parents'), each with its own broker and prefix.
  Settings already store a profile id, so this is a list and a switcher on top of what
  exists.
- **MQTT over WebSocket (wss, port 8084)** for networks that block port 8883.
- **Client certificates (mTLS)** for brokers that require them.

## More from the board

The firmware's MQTT bridge already publishes ad-blocking data that the app can show
without firmware changes:

- **Blocking switch**: `<prefix>/blocking` (`ON`/`OFF`, retained) and
  `<prefix>/blocking/set`, so the app can pause and resume blocking.
- **Stats**: `<prefix>/stats` every 60 s (queries blocked/allowed, blocked share,
  blocklist size, active clients) for a small stats card.

These would need firmware changes **(firmware)**:

- Board health: uptime, Wi-Fi signal, firmware version, update available.
- Per-PC details: last time seen online, IP and MAC address, last wake result as seen by
  the board.
- **Shutdown or sleep** of a PC, via a small companion agent on the PC that listens to an
  MQTT topic.

## Integrations

- **Intents for automation apps** (Tasker, Home Assistant companion, NFC tags): a
  documented `io.github.joanrra2.pocketwake.WAKE` action with the PC name.
- **Deep links** such as `pocketwake://wake/OFFICE-PC`, for buttons in other apps.
- **Wear OS tile** with the favourite PCs.

## Quality

- Translations: Spanish first.
- Accessibility pass (TalkBack labels for every status, large font sizes).
- F-Droid metadata and reproducible builds.
