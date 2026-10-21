# Offline MIDI Karaoke — Android starter

A lightweight, landscape-first Android karaoke interface written in native Kotlin and XML. The project deliberately uses no runtime libraries beyond the Android framework.

## Included in this prototype

- Generated catalog support for 47,997 local MIDI files
- Search by song number, title, or artist using a virtualized list
- Add and remove songs from the queue
- Start a queued song or play a number immediately
- Basic MIDI playback through Android's platform MIDI support
- Automatic extraction and synchronized display of embedded MIDI lyrics
- Bottom-positioned two-line lyric pages with heavy type, bright-blue highlighting, and no repeated row promotion
- Swipe-left reservation drawer with numeric song entry and live `R` queue count
- Compact key, tempo, microphone-monitor volume, and playback controls inside the swipe drawer
- Tap-outside dismissal for the swipe reservation drawer
- Reserved-song artwork with a live count badge and adjacent next-song title/artist preview
- Muted, looping video backgrounds behind the karaoke lyrics
- Real playback duration, seeking, pause, and restart
- Pause, restart, seek, key, and tempo controls
- Prototype score screen
- Compact generated catalog and seekable compressed song pack
- Android TV/tablet-friendly landscape layout
- Same-network browser remote for searching and reserving songs without interrupting playback
- Persistent browser-remote enable/disable control and adjustable remote queue limit

Android supports MIDI Type 0 and Type 1 playback, but the exact instrument sound is supplied by the device. Real key/tempo transformation, a bundled SoundFont synthesizer, and microphone pitch analysis are not implemented yet. The displayed score remains a deterministic placeholder.

## Why Kotlin and XML

- Native Android access for MIDI, audio, USB/SD storage, and microphones
- Smaller dependency footprint than a cross-platform stack
- XML views avoid adding the Compose runtime to this initial build
- R8 and resource shrinking are enabled for release builds

## Open and run

1. Install a current Android Studio with Android SDK 35 and JDK 17.
2. Open this folder as an Android project.
3. Allow Android Studio to sync Gradle.
4. Run the `app` configuration on an API 26+ landscape emulator, tablet, or TV box.

The repository contains the Gradle wrapper configuration, but not the binary wrapper JAR. Android Studio can provision the project tooling; alternatively generate the wrapper with Gradle 8.11.1.

## Rebuild the embedded song pack

Place MIDI files in `songs/` using the filename format:

```text
000001 - Song Title - Artist.mid
```

Then regenerate the compressed pack and catalog:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Generate-SongCatalog.ps1
```

The generated pack stores each MIDI as an independent gzip member. The catalog contains its offset and length, so the app extracts only the selected song instead of unpacking the entire library.

The large local `songs/` collection, generated `songs.kpack`, background videos, APK outputs, and source archive are intentionally excluded from Git. Copy or generate those assets locally before building a fully populated karaoke APK. The lightweight generated `song_catalog.tsv` is included so the library and browser search can be inspected without committing the 2+ GB source collection.

## Browser reservation remote

On the home screen, tap **REMOTE OFF**, enable browser reservations, and choose a queue limit from 1 to 50 songs. Connect the guest device to the same Wi-Fi network or hotspot and open the address shown by the app, such as `http://192.168.1.20:8765`.

The guest page searches the embedded catalog by song number, title, or artist and silently adds accepted requests to the main queue. The remote is local-network only, requires no companion app or internet service, and remains available while the karaoke app is open.

## Add video backgrounds

Place reusable video files in `Background/` and rebuild the app. MP4 is recommended for broad Android compatibility. The folder is packaged directly as app assets, so no source-code edit is needed when adding more videos.

The app discovers supported video files at startup, mutes and loops the selected video, and assigns songs across the available backgrounds by song number. With one file, that same background is used for every song; with 6-10 files, songs are distributed across them automatically.

## Next implementation stages

1. Integrate a consistent software MIDI synthesizer and redistributable SoundFont.
2. Define the MIDI convention for the silent vocal melody/reference track.
3. Add microphone permission, real-time pitch detection, calibration, and scoring.
4. Move the catalog from the generated TSV to an indexed SQLite database.
5. Add signed song-pack import from internal storage, SD card, and USB.

For general distribution, external installable song packs are preferable to a very large APK. This prototype embeds the complete local collection because it is intended for direct offline installation.
