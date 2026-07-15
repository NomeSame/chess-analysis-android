# ♟️ Chess Analysis

> **Status: Work in Progress — developed on and off.** The app is fully usable today, but it still grows in bursts: I add and polish features when I have the time, so expect the occasional rough edge between updates.

An **on-device Android chess app** for playing, analyzing, and studying — powered by a full **Stockfish 18** engine that runs entirely on your phone. No account, no server: the engine, the analysis, even the optional AI coach all run locally.

<!--
  SCREENSHOTS — biggest single upgrade to this page.
  Drop 2–3 PNGs into a docs/ folder in the repo, then uncomment:
  ![Board](docs/board.png)
  ![Analysis](docs/analysis.png)
  ![AI Coach](docs/coach.png)
-->

## What it does

- **Play vs the engine** — pick a strength (ELO, up to full Stockfish), choose which side the engine plays, or set up any position and *play from here*.
- **Analyze a full game** — move-by-move review with accuracy %, an evaluation bar, best-move arrows, top-3 candidate bars, and per-move labels (Brilliant, Great, Best, Book, Inaccuracy, Mistake, Blunder…).
- **AI Coach** — plain-language explanations of your moves. Runs fully on-device (Gemma 3 1B/4B via a bundled llama.cpp runner), through your own OpenAI-compatible API key (e.g. a local LM Studio server), or falls back to free Lichess analysis.
- **Import any position** — paste a PGN, load a FEN, or **import a screenshot**: the app recognizes the board from an image and learns from your corrections.
- **Openings & theory** — offline opening book with opening names, plus a *Learn theory* mode showing each side's plans and main continuations.
- **Puzzles** — Lichess puzzles filtered by rating and theme (mates, forks, pins, sacrifices, endgames…).
- **Make it yours** — board themes, several piece sets, move sounds, legal-move hints, adjustable analysis depth, English/German UI.

## How to use it

1. **New Game** — tap *New Game* to start from the standard position. Move by tapping/dragging pieces; legal moves are previewed if enabled.
2. **Play vs the engine** — *Setup Board* → *Play from here* → *VS Engine*, then set the engine's strength and which color it plays.
3. **Analyze** — during or after a game, tap *Analyze game*. Step through with the review controls; the eval bar, arrows and move labels show what was best and where it went wrong.
4. **Import** — *Import* → paste a PGN, enter a FEN, or upload a screenshot of a board to bring in a game from elsewhere.
5. **Coach** — enable *AI Coach* in Settings and pick a backend (on-device model, your own API, or Lichess).
6. **Puzzles / Theory** — open *Puzzles* to train tactics, or *Learn theory* to study openings.
7. **Settings** (side drawer) — themes, pieces, sounds, analysis depth, eval/quality display, opponent strength, language.

## Build it yourself

Requirements: a recent Android Studio, an **arm64-v8a** device or emulator, Android 7.0+ (minSdk 24). An internet connection is needed on the first build.

```bash
git clone https://github.com/NomeSame/chess-analysis-android.git
cd chess-analysis-android
./gradlew assembleDebug
```

- The **Stockfish 18 big NNUE network** is downloaded automatically at build time into `app/src/main/res/raw`.
- The on-device **AI Coach (llama.cpp)** is **off by default** for faster builds. Enable it with `-PaiCoachLlama=true` (or set it in `gradle.properties`); CMake then clones the pinned llama.cpp tag.
- The built APK lands in `app/build/outputs/apk/`.

## How this was built

I work idea-first: **I owned the concept, the architecture direction, the product decisions, and the testing** — deciding what to build, how it should feel, and pushing on it until bugs were fixed and features actually held up. **The code itself was written by an AI assistant (Claude) under that direction.**

I'm putting this front and center on purpose. In an open-source repo the commit history says so anyway, and I'd rather be straight about where the value sits: in the judgment, not the typing.

## Tech

Kotlin (UI) · C++ (Stockfish 18 engine + llama.cpp, via NDK/CMake JNI) · Python (tooling) · TensorFlow Lite (screenshot piece recognition) · runs on-device.

## License & credits

Licensed under **GPL-3.0-or-later** — because this app includes **Stockfish**, which is GPLv3, any distributed work that bundles it must carry the same license.

- **Stockfish 18** — chess engine, GPLv3 · [stockfishchess.org](https://stockfishchess.org)
- **llama.cpp** — on-device LLM runner, MIT
- **Gemma 3** — Google, under the Gemma Terms of Use
- **Lichess** — puzzle & opening data
