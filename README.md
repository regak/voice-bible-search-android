# Voice Bible Search (Android)

Native Kotlin + Jetpack Compose app for voice-searching Swahili Bible
verses. Tap the orb, speak a query ("Nitafutie Yohana 3:16" or "Nisomee
mstari kuhusu upendo"), and Gemini Live answers back in Swahili after
calling a local, fully offline `search_bible_verse` tool.

## Architecture

- **Voice pipeline**: raw WebSocket client to Gemini Live's
  `BidiGenerateContent` endpoint (`GeminiLiveClient.kt`), 16kHz PCM16 mic
  capture (`AudioCapture.kt`), 24kHz PCM16 playback (`AudioPlayback.kt`).
  Ported from the sibling `voice-conversation-app-android` /
  `VoiceModeApp` projects — same protocol lessons (header-based API key
  auth, setup-before-audio handshake, server-driven barge-in).
- **ALL** voice queries, including exact references like "Yohana 3:16",
  go through Gemini Live's tool-calling (`search_bible_verse` function
  declaration in the `setup` message). There is **no on-device
  grammar/parsing shortcut** and **no offline STT fallback** — voice
  always requires network.
- **Bible data**: `search_bible_verse` is backed by a fully offline
  SQLite database (`app/src/main/assets/bible.db`, ~9.5MB, 31,103
  verses across all 66 books) with a plain indexed table for exact
  book/chapter/verse lookups and an FTS4 virtual table for topical
  search. The database is built once, offline, by
  `scripts/build_bible_db.py` (not shipped/run on-device) and copied
  to app storage on first launch (standard prepackaged-SQLite pattern,
  see `BibleRepository.kt`).
- Single translation for v1: Biblica® "Toleo Wazi Neno: Maandiko
  Matakatifu" (Open Kiswahili Contemporary Scriptures), CC BY-SA 4.0 —
  see the in-app Credits screen for the full attribution/trademark
  notice required by the license.

## API key setup

```
sdk.dir=/path/to/android/sdk
GEMINI_API_KEY=your-free-tier-key-from-aistudio.google.com/apikey
```

Create `local.properties` in the project root (gitignored, never
committed) with the above. `app/build.gradle.kts` reads it via a
`java.util.Properties()` loader defaulting to an empty string, so a
clean checkout with no key still compiles — `BuildConfig.GEMINI_API_KEY`
is just blank and the app shows an error asking for a key instead of
connecting.

## Building (headless, no Android Studio)

```
export JAVA_HOME=/opt/data/android-toolchain/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:/opt/data/android-toolchain/gradle-8.9/bin:$PATH
export ANDROID_HOME=/opt/data/android-toolchain/sdk
gradle assembleDebug
```

## Swahili STT/TTS smoke test (findings)

Before building the full pipeline, 3 short Swahili phrases were
synthesized with gTTS and streamed as 16kHz PCM16 into a real Gemini
Live session (`gemini-3.1-flash-live-preview`) over a free-tier API key,
with both input/output transcription enabled, to sanity-check Swahili
quality on Gemini Live ahead of committing to this architecture:

- "Mungu ni upendo" transcribed perfectly cleanly and got a correct,
  well-formed Swahili response citing 1 Yohana 4:8.
- "Nitafutie Yohana tatu kumi na sita" and "Nisomee Zaburi ishirini na
  tatu" — the *displayed* `inputTranscription` text was garbled
  (interspersed with stray Japanese characters, likely a gTTS Swahili
  voice/audio-artifact issue rather than a Gemini language problem) —
  but the model's actual **response** was correct both times (quoted
  the real John 3:16 and the full Psalm 23 text verbatim in Swahili),
  meaning the underlying audio-understanding correctly parsed intent
  even when the transcript display was noisy.
- All three turns produced real audio output (300KB–2.3MB of PCM16),
  confirming Gemini Live's AUDIO response modality works for Swahili.

Takeaway: Gemini Live's Swahili comprehension looks solid for this use
case even under imperfect input audio, but the `inputTranscription`
field shown in any debug UI should not be trusted as ground truth for
STT quality — it can look wrong while the model's actual understanding
is right. Real human-voiced Swahili (vs. gTTS) should be re-tested by
Alexander on a real device before relying on the transcript display for
anything user-facing.

## Status / known gaps

- Scaffold builds (`assembleDebug`); not yet tested on a physical
  device (no emulator/ADB in this build environment).
- `local.properties` in this initial push has an empty
  `GEMINI_API_KEY` placeholder — supply your own free-tier key to
  actually run the voice pipeline.
