# Wrist MP3

A standalone MP3 and M4A player for Wear OS, designed for the Samsung Galaxy Watch Ultra. Music is stored and played directly on the watch and does not require a phone.

## Features

- On-watch library with MP3 and DRM-free M4A title, artist, and duration metadata
- Remembered library sorting by title, artist, album/track, track number,
  recently added, or filename
- iPod-style hierarchical browsing for albums, artists, songs, and recent
  additions, with native bezel/crown scrolling
- Background playback through Android Media3 and system media controls
- Play/pause, previous/next, 15-second seek, shuffle, and repeat controls
- Temporary Wi-Fi upload page protected by a per-session access code

## Transfer music

The app provides a temporary Wi-Fi upload page:

1. Connect the watch and a computer to the same Wi-Fi network.
2. Open **Transfer music** on the watch.
3. Enter the displayed address in the computer's browser.
4. Drop one or more MP3/M4A files or folders onto the page. Dropped folders are
   searched recursively, and unsupported files are listed as rejected.
5. Tap **Done** on the watch to shut down the upload server.

Uploads require the randomly generated six-digit session code shown on the watch. Files are written to a temporary file first, limited to 1 GB each, and saved in the app's private `music` directory without overwriting existing tracks.
Exact duplicate files are detected by content. A repeated upload is skipped, and
older duplicates already on the watch are shown only once without deleting the
underlying files.
The transfer page reports a written reason for every rejected file rather than
using color as the only status indicator.

## Build requirements

- Android Studio with Android SDK 37
- JDK 17
- A Wear OS 3+ watch or emulator (minimum API 30)

Open the project in Android Studio, allow Gradle sync to finish, and run the `app` configuration on the watch. The Gradle 9.6 wrapper is included.

## Included test emulator

This workspace includes a local Android toolchain and a large-round Wear OS 3
virtual watch named `wrist_mp3_wear3`. It does not modify a system-wide Android
Studio or SDK installation.

Start the virtual watch in one terminal:

```bash
scripts/start-wear-emulator.sh
```

If no desktop display is available, start it headlessly instead:

```bash
scripts/start-wear-emulator.sh --headless
```

Then build, run the unit tests, install the APK, and open Wrist MP3 from a
second terminal:

```bash
scripts/build-and-run.sh
```

The launcher uses hardware acceleration when `/dev/kvm` is available and
automatically falls back to software emulation otherwise. Software startup can
take several minutes.

## Possible follow-ups

- Delete and rename tracks from the watch
- Album artwork and album/artist browsing
- Transfer progress and storage-usage display
