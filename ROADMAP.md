# Roadmap

The MVP covers one board, one-tap wake with confirmation, and setup with a connection
test. Below are the use cases the code is already shaped for, roughly in the order they
make sense. Items marked **(firmware)** also need a change in the Pocket AdBlock
firmware.

## Phase 2: faster wake

- **Home-screen widget**: one button per favourite PC with its online dot. Tapping
  connects briefly, sends the wake, and shows the result on the widget.
- **Quick Settings tile**: "Wake OFFICE-PC" from the notification shade. Long-press opens
  the app.
- **Launcher shortcuts**: long-press the app icon for "Wake <PC>" (dynamic shortcuts
  for the last-woken PCs, and pinned shortcuts).
- **QR setup** **(firmware)**: the board's web UI shows a QR code with the broker host,
  port, TLS and prefix (never the board's password). The app scans it and only asks for
  the phone's login.

All of these connect only for the few seconds a wake takes; still no permanent
background service.

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

## More from the board **(firmware)**

The bridge could publish more, and the app would show it on the board card:

- Ad-blocking stats (queries today, % blocked) and a **pause blocking for 5 min** button.
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
