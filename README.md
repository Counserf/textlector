# TextLector

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/compose-multiplatform/)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-green.svg)]()
[![Offline](https://img.shields.io/badge/Offline-100%25-brightgreen)]()
[![Latest Release](https://img.shields.io/github/v/release/nedmah/TextLector)](https://github.com/nedmah/TextLector/releases)

A free, offline text-to-speech reader for Android and iOS, built with Kotlin Multiplatform and Compose Multiplatform.

> Open-source alternative to Speechify — no subscriptions, no internet required, no data leaves your device.

---

## Demo

https://github.com/nedmah/TextLector/releases/download/v1.3.0/TextLector.demo.mp4

---

## Screenshots

<p float="left">
  <img src="screenshots/library.png" width="200"/>
  <img src="screenshots/reader.png" width="200"/>
  <img src="screenshots/settings.png" width="200"/>
</p>

---

## Features

- **Import** — PDF, TXT, EPUB, FB2, URL, or paste text manually
- **Offline TTS** — three engines: system voices, Piper (neural, ~63 MB), Supertonic (highest quality, ~380 MB)
- **Paragraph highlighting** — current paragraph highlighted in sync with playback
- **Reading progress** — app remembers where you stopped in every document
- **Library** — manage documents, mark favorites, sort by date
- **Adjustable playback** — speed control (0.5x–2.0x), voice gender, language, font size
- **Switch engines at runtime** — no app restart required

---

## TTS Architecture

TextLector uses a three-tier TTS system managed by a single `SwitchableTtsEngine` interface.

**Tier 1 — System TTS (default)**
Android `TextToSpeech` and iOS `AVSpeechSynthesizer` — available immediately, no downloads.

**Tier 2 — Neural TTS via Piper / sherpa-onnx**
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) bundles a pre-compiled ONNX Runtime and eSpeak-NG. Piper VITS models (~63 MB each) are downloaded on demand from HuggingFace and stored locally.

| Voice  | Language | Gender |
|--------|----------|--------|
| Ruslan | Russian  | Male   |
| Irina  | Russian  | Female |
| Ryan   | English  | Male   |
| Lessac | English  | Female |

**Tier 3 — Neural TTS via Supertonic**
[supertonic-kmp](https://github.com/nedmah/supertonic-kmp) — a KMP wrapper around [Supertonic v3](https://github.com/supertone-inc/supertonic) by Supertone Inc. Flow-matching neural synthesis with 10 bundled voice presets (M1–M5, F1–F5) for Russian and English. One-time model download (~380 MB), then fully offline.

Compared to Piper: higher naturalness, but slower generation on mobile CPU. Tune `inferenceSteps` (2–12) to trade quality for speed.

Both ONNX-based engines use a shared `TtsQueue` that prefetches the next paragraph in the background while the current one plays.

**Known limitation:** sherpa-onnx and supertonic-kmp both bundle `libonnxruntime.so`. Resolved via `jniLibs.pickFirsts` in `build.gradle.kts` combined with the static-link sherpa-onnx AAR which embeds ORT statically, eliminating the symbol conflict.

---

## Tech Stack

| Layer         | Technology                                                 |
|---------------|------------------------------------------------------------|
| UI            | Compose Multiplatform                                      |
| Architecture  | MVI + ViewModel (commonMain)                               |
| DI            | Koin Multiplatform                                         |
| Navigation    | Navigation Compose CMP (Nav2.8, typesafe routes)           |
| Database      | SQLDelight 2.x                                             |
| Preferences   | multiplatform-settings                                     |
| File I/O      | okio                                                       |
| HTTP          | Ktor Client 3.x                                            |
| HTML parsing  | Ksoup (Jsoup KMP port)                                     |
| Neural TTS    | sherpa-onnx (Piper VITS) + supertonic-kmp (Supertonic v3)  |
| PDF (Android) | PdfBox-Android                                             |
| PDF (iOS)     | PDFKit + Vision OCR fallback                               |

---

## Project Structure

```
composeApp/
├── commonMain/         # shared UI, ViewModels, domain, data
│   ├── domain/         # models, repository interfaces, use cases
│   ├── data/           # SQLDelight, repository implementations
│   ├── ui/             # Compose screens and components
│   └── platform/       # expect declarations (TTS, FileReader, etc.)
├── androidMain/        # Android actuals + sherpa-onnx engine
├── iosMain/            # iOS actuals
└── jvmMain/            # Desktop (planned)

iosApp/
├── TTS/                # IosSherpaEngine, SherpaOnnxTtsBridge
├── PDF/                # PdfTextExtractor, IosPdfPageExtractor
└── Utils/              # IosFileDownloader, IosTarExtractor
```

---

## Building

### Prerequisites

- Android Studio Meerkat or later
- Xcode 15+
- JDK 17+
- Kotlin 2.0+

### Android

```bash
./gradlew :composeApp:assembleDebug
```

### iOS

Open `iosApp/iosApp.xcodeproj` in Xcode and run on a simulator or device.

> The sherpa-onnx XCFramework is included via CocoaPods / local framework — no additional setup required.

### Shared KMP module

```bash
./gradlew :composeApp:compileKotlinAndroid
./gradlew :composeApp:iosArm64MainKlibrary
```

---

## Roadmap

- [x] PDF, TXT, EPUB, FB2, manual text import
- [x] URL import
- [x] Native TTS (Android + iOS)
- [x] Neural TTS via Piper / sherpa-onnx (Android + iOS)
- [x] Camera / OCR import
- [x] Neural TTS via Supertonic — flow-matching, RU + EN
- [ ] Background playback (MediaSession / AVAudioSession)
- [ ] JVM / Desktop support

---

## License

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)