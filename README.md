<div align="center">

<img src="docs/app-icon.png" alt="ChessAnalysis app icon" width="120" />

# ChessAnalysis

**An offline Android chess analysis app powered by a full Stockfish 18 engine running natively on the phone — no server, no account.**

Kotlin · C++17 (NDK) · Stockfish 18 + NNUE · llama.cpp · TensorFlow Lite

</div>

---

> ### 🚧 Status: Work in Progress (portfolio project)
> - **Goal:** a self-contained Android chess trainer — strong on-device analysis, a game reviewer (Chess.com-style move classification), an opening/theory explorer, and a tactics trainer.
> - **Current state:** the **Stockfish analysis core, game review, opening explorer, PGN import/export, sound themes and puzzle trainer are implemented** and integrated. Two features are **experimental**: the on-device AI coach (llama.cpp) and the ML screenshot recognizer (TFLite) — see [Status & known limitations](#status--known-limitations).
> - **Honest caveat:** the dev environment has **no Android SDK/device**, so recently added features are code-complete and statically reviewed but await on-device verification. This is labelled per-feature below rather than hidden.
> - **What I'm learning here:** JNI bridging a large C++ engine into Android, NNUE evaluation, on-device LLM inference (GGUF/llama.cpp), and turning raw engine output into human-readable coaching.

## What it does

Import or play a game, and the app evaluates every position locally with **Stockfish 18** (NNUE), classifies each move (Brilliant → Blunder, Chess.com-like), draws the eval curve, names the opening, and lets you explore variations — entirely offline.

## Features

- **Full-strength engine analysis** — the complete Stockfish 18 engine (search + NNUE evaluation + Syzygy probing) compiled natively via the NDK and driven over UCI through a JNI bridge. Adjustable opponent strength (ELO).
- **Game review & move classification** — Chess.com-style per-move labels (Brilliant/Great/Best/…/Blunder) from a win%-drop model, accuracy scores, eval chart with clickable move markers, and tactic detection.
- **Opening explorer & theory** — Lichess Masters explorer (online) plus an offline opening book (Lichess openings TSV) and 100 curated opening entries with plans/traps, localized EN/DE.
- **Play vs. engine** — pick a side and strength; sub-1350 ELO uses a custom softmax weakening so weak levels feel human.
- **PGN import/export**, game history persistence, FEN setup board, and move list navigation with variations.
- **Puzzle trainer** — Lichess-puzzle tactics mode (264 CC0 seed puzzles bundled, more downloadable) with ELO/theme filters. *(implemented; device test pending)*
- **Switchable sound themes** and an SVG-based board rendering pipeline with multiple piece sets.
- **Localized UI (DE/EN)** that persists across restarts via `setApplicationLocales`.
- **On-device AI coach (experimental)** — a bundled `llama.cpp` runner loads a small GGUF model (Gemma 3 1B) to turn engine output into plain-language coaching, fully offline. See caveats below.
- **ML piece recognizer (experimental)** — reads a position from a board screenshot; ships with a heuristic pipeline and an optional TFLite path (no trained model bundled yet → heuristic is active).

## How it works

```
┌─────────────────────────────┐
│  Kotlin / Android UI layer  │  MainActivity, coroutines, custom board view
└──────────────┬──────────────┘
               │ JNI
      ┌────────┴─────────┐
      │  Native (C++17)  │
      │  stockfish_jni   │──►  Stockfish 18 (search, NNUE, Syzygy)
      │  llama_jni       │──►  llama.cpp → Gemma 3 GGUF   (AI coach, optional)
      └──────────────────┘
```

- **`stockfish_jni`** compiles the Stockfish 18 sources into a shared library and drives it over UCI from Kotlin; UCI I/O is redirected into thread-safe command/response queues.
- **`llama_jni`** is built only when the AI-coach flag is on; CMake clones a pinned `llama.cpp` tag (`b5050`, chosen for Gemma 3 support) at configure time.
- Native targets build for **arm64-v8a** (NEON + dotprod); the large NNUE network is downloaded at build time (the small one is committed).

## Tech stack

| Area | Technology |
|---|---|
| App / UI | Kotlin, AndroidX, Material Components, ConstraintLayout, RecyclerView, Coroutines |
| Chess engine | Stockfish 18 (C++17), NNUE, Syzygy tablebases, via JNI |
| On-device LLM | llama.cpp (GGUF), Gemma 3 1B — optional |
| ML | TensorFlow Lite (piece classifier, heuristic fallback) |
| Native build | CMake 3.22, Android NDK, C++17 |
| Min / target SDK | 24 / 34 |

## Building & running

Requirements: **Android Studio** (recent), **Android NDK + CMake**, and an **arm64 device or emulator** (release builds target `arm64-v8a`).

```bash
git clone https://github.com/NomeSame/chess-analysis-android.git
```

Open the project in Android Studio and run it on a device. On the first build Gradle downloads the large NNUE network into `app/src/main/res/raw/` (needs internet once).

### AI coach (optional, build flag)

The on-device LLM coach is gated by a **build flag** because it compiles the native `llama.cpp` runner (larger APK, slower first build — CMake clones `llama.cpp` at configure time).

- **On (default in this repo):** `aiCoachLlama=true` in `gradle.properties`. The runner is compiled in; you then enable the coach and pick/download the Gemma model **inside the app** (Settings → AI coach), and it loads automatically on the next app start.
- **Off (fast dev builds):** build with `-PaiCoachLlama=false`. The app still runs; the coach cards show *"disabled in this build."*

```bash
./gradlew assembleDebug -PaiCoachLlama=false   # fast build, coach compiled out
```

> An in-app on/off switch (so the coach can be toggled at runtime without rebuilding, provided the runner is compiled in) is planned — see `TODO.md → AI-Coach`.

## Deployment

This is a native Android app, so "deployed" means an **installable APK** rather than a hosted URL.

> ⚠️ No prebuilt APK is attached yet — the dev environment has no Android SDK to produce a signed build. Building from source (above) is currently the way to run it. Cutting a signed `arm64-v8a` release is tracked on the [Roadmap](#roadmap).

<!-- Once built: attach the APK to a GitHub Release and link it here →
     👉 Download the latest APK from the [Releases](../../releases) page. -->

## Status & known limitations

The Stockfish analysis core is the solid, finished part. The two ML-driven features are wired in but not finished, and are documented honestly here rather than presented as done.

**AI coach (llama.cpp) — experimental, not yet dependable.**
- Inference can be slow, so it hasn't been fully validated end-to-end on-device.
- Output quality still needs prompt/model tuning; small models tend to over-explain instead of giving the short, focused lines this use-case needs.
- Next steps: get inference to interactive latency, then tune prompt/model for short, useful coaching.

**Piece recognizer (TFLite) — heuristic active, model not bundled.**
- The heuristic pipeline (board detection, perspective, template matching, correction learning) works; the TFLite path is dormant because no trained `chess_cnn.tflite` is bundled — so recognition currently runs on the heuristic only.
- Improvement path is a correction loop: import a board image → fix wrongly detected pieces → load → export the correction → re-bundle so the learned recognition becomes the default.

**Recently added, device-test pending (no SDK in dev env):** puzzle trainer, sound themes, and several screenshot-recognition fixes are code-complete and statically reviewed but await on-device verification.

## Roadmap

- [ ] Make the AI coach fast and useful (latency + output quality) and expose an in-app on/off toggle.
- [ ] Calibrate the piece recognizer and bake in the improved default.
- [ ] Add screenshots / demo GIF (needs a device build).
- [ ] Publish a signed `arm64-v8a` APK release.
- [ ] Add a GPL-3.0 `LICENSE` file.

## License

Not yet licensed. **Stockfish is GPLv3**, and this project bundles its source, so the intended license is **GPL-3.0** — a `LICENSE` file will be added (tracked on the Roadmap). Some bundled sound assets are derived from reference audio whose licensing the author still needs to clear before a public release; only the self-synthesized "Classic" sound set is unambiguously redistributable.
