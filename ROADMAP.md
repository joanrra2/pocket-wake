# Roadmap

Current status: **0.2**, as an Android app and a web app (iPhone, iPad, any
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
- ~~**Launcher shortcuts**~~: done in 0.2. Long-press the app icon for the last woken PCs,
  or pin a "Wake <PC>" icon from the PC's menu.

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

- ~~**Blocking switch**~~ and ~~**stats**~~: done in 0.2 (the *Ad blocking* card, Android
  and web), from `<prefix>/blocking`, `<prefix>/blocking/set` and `<prefix>/stats`.

Coming with Pocket AdBlock firmware 0.6.0 **(firmware)**; the app adds each one once its
topics are final:

- ~~**Wake timeout per PC and Cancel**~~: done in 0.3 from `<prefix>/wol/<name>/result`.
  The app follows the board's own wait and result, and shows Cancel once the board says
  `waking` (older firmware would treat `cancel` as another wake).
- ~~**Watch-only devices**~~: done in 0.3.1. A retained `watch only` result hides the Wake
  button; an empty result (a MAC was added) brings it back.
- **Push alerts without a broker account (ntfy)**: the board can post "PC online",
  "wake result", "update failed" and "restarted" to an ntfy topic it generates. The ntfy
  app (or the web app) then notifies even when Pocket Wake is closed, which Pocket Wake
  itself can't do without a background service. ntfy could also become a second way to
  wake (a "wake NAME" command), for people who don't want an MQTT broker.
- **Watch-only hosts** (NAS, printer, a server): online/offline dot, no Wake button.
- **Board health over MQTT** with a real clock: firmware version, uptime, last restart
  and its reason, Wi-Fi signal, update available. ~~Uptime and last restart~~ are done in
  0.2.1 (from `<prefix>/stats`, on the *Ad blocking* card); the rest waits for the firmware.

Still open **(firmware)**:

- Per-PC details: last time seen online, IP and MAC address, last wake result as seen by
  the board.
- **Shutdown or sleep** of a PC, via a small companion agent on the PC that listens to an
  MQTT topic.

## Integrations

- ~~**Intents for automation apps**~~ and ~~**deep links**~~: done in 0.2.
  `io.github.joanrra2.pocketwake.WAKE` with the extra `name`, or
  `pocketwake://wake/OFFICE-PC`; both ask before waking.
- **Wear OS tile** with the favourite PCs.

## Quality

- Translations: Spanish first.
- Accessibility pass (TalkBack labels for every status, large font sizes).
- F-Droid metadata and reproducible builds.
