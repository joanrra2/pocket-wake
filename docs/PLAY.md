# Publishing on Google Play

A checklist for getting Pocket Wake onto Google Play, and for each release afterwards.
The GitHub APK stays available as well. Both are signed with the same key, so either one
can update the other.

## One-time setup

### 1. Developer account

- **Personal account:** before the app can go public, it needs a closed test with at
  least **12 testers opted in for 14 days in a row** (step 6).
- **Organization account:** no tester rule, and the store shows the organization's name.
  Needs a D-U-N-S number (free, takes a few days).

The developer name and contact details you enter here appear on the store page. The
privacy policy must name the same developer.

### 2. Create the app

Play Console → *Create app*: name **Pocket Wake**, default language English (United
States), *App*, *Free*. The package name is set by the first upload and can never change
afterwards: `io.github.joanrra2.pocketwake`.

### 3. Signing: keep the existing key

When Play App Signing asks for the app signing key, choose **Use a different app signing
key → Export and upload a key from Java keystore**. Then upload the key from
`pocket-wake-signing/release.jks` with the PEPK tool and command that the Console shows.

Use that same key as the upload key. CI then needs no new secrets.

> Don't let Google generate a new app signing key. If it does, the Play version and the
> GitHub APK have different signatures: neither can update the other, and moving between
> them means uninstalling (and setting the app up again).

The certificate's SHA-256, for checking that the Console shows the right key, is in
`pocket-wake-signing/LEEME.txt`.

### 4. App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | `https://joanrra2.github.io/pocket-wake/privacy.html` ([source](../web/privacy.html)) |
| Ads | No ads |
| App access | Some functionality is restricted: see [Reviewer access](#reviewer-access) |
| Content rating | Category *Utility, productivity, communication or other*; *No* to every content question |
| Target audience | 18 and over (keeps the app out of the Families policy) |
| Data safety | No data collected, no data shared (see below) |
| News app / government app / health / financial features | No |

**Data safety.** The app sends the broker login and wake requests only to a broker the
user enters. Nothing reaches the developer or any service the developer chose, and there
are no SDKs that send data. Answer *No* to "Does your app collect or share any of the
required user data types?". Under security practices: data is encrypted in transit (TLS),
and users can delete it by uninstalling the app. The form's own help text has the final
word on the definitions; re-read it when submitting.

### 5. Store listing

Text and screenshots are in [`fastlane/metadata/android`](../fastlane/metadata/android),
in the layout that Play's upload tools and F-Droid both read:

| File | Play Console field | Limit |
|---|---|---|
| `title.txt` | App name | 30 |
| `short_description.txt` | Short description | 80 |
| `full_description.txt` | Full description | 4000 |
| `en-US/images/phoneScreenshots/*.png` | Phone screenshots (1080 × 1920) | 2–8 |

Spanish goes under *Manage translations → Spanish (Spain) – es-ES*. The app itself is
still English-only, and the Spanish description says so.

To redraw the screenshots after a UI change, run:

```bash
./gradlew recordRoborazziDebug --tests '*ScreenshotTest.play*'
```

Still to make by hand: the **512 × 512 app icon** (`web/icons/icon-512.png` is a start)
and the **1024 × 500 feature graphic**.

### 6. Closed test (personal accounts only)

*Test and release → Closed testing*: create a track, add the testers (a Google Group is
easiest), upload a release (below), and share the opt-in link. After 14 days with at
least 12 testers opted in, *Apply for production access* appears on the Dashboard. Ask
the testers to actually use the app now and then.

## Each release

1. Tag the release on `main` as usual (`git tag v0.2.1 && git push origin v0.2.1`).
2. The **Build** workflow signs the APK for the GitHub release, and also an app bundle.
   Download the `pocket-wake-v0.2.1-play` artifact from the run: it contains
   `pocket-wake-v0.2.1.aab`.
3. Play Console → the track (closed, open or production) → *Create new release* →
   upload the `.aab` and add the release notes.

`versionCode` is the workflow's run number, so every CI build is higher than the one
before. Only upload bundles built by CI: a local build has `versionCode` 1, and Play
would refuse it.

## Reviewer access

Google's reviewers must be able to try the app, and it does nothing without a broker and
a board. Under *App access*, give them a broker login that works, and a board that
answers on it.

**Plan: a demo board on its own broker login.**

- A spare Pocket AdBlock board, on its own topic prefix (for example `demo`), kept
  powered on while the app is in review and afterwards (reviews of later updates use it
  too).
- On the board: a couple of PCs with made-up MAC addresses, plus a watch-only device
  whose IP always answers (the router), so the reviewer sees an online dot.
- On the broker: a `play-review` login allowed only `demo/#`, never the real prefix.
  That way a reviewer can't wake anything real.
- The *App access* instructions (no password in this file; it goes only into the Play
  Console):

  > Pocket Wake controls a Pocket AdBlock board through an MQTT broker. A demo board is
  > online for review. Open the app → *Set up broker*, and enter: Broker address
  > `<host>`, Port `8883`, TLS on, Username `play-review`, Password `<password>`, Topic
  > prefix `demo`. Tap *Test connection*, then *Save*. The home screen lists the demo
  > PCs; tap *Wake* on one to send a wake request and follow its progress.

If no spare board is available, a small always-on script that answers like a board can
take its place. `BrokerIntegrationTest` already contains one, and it would need a
machine that stays on.
