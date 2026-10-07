# Chess Analysis

Play a game, bring in a PGN from elsewhere, or set up the position you want to understand. Chess Analysis runs Stockfish 18 on your Android phone and lets you step through the moves, try alternatives, and save the result for later.

The aim is to go from "that move was a mistake" to seeing what you could have played instead, without needing a chess account. It is still a work in progress: the engine and review tools are usable, while move classification, screenshot recognition, and the optional coach continue to change.

[Download the APK](https://github.com/NomeSame/chess-analysis-android/releases/latest) - [Browse the source](https://github.com/NomeSame/chess-analysis-android)

## Play, review, come back to it

- **Play against Stockfish.** Choose the engine's side and strength, start a normal game, or arrange a position and play from there. Optional clocks, resignation, and draw detection are included.
- **Review a whole game.** See an evaluation chart, accuracy for each side, candidate moves, best-move arrows, and labels such as Book, Best, Brilliant, Miss, Mistake, and Blunder. The horizontal move cards show full move numbers and colored quality badges.
- **Explore another line.** Step back to a position and try a different move. Review, exploration, and theory evaluations use the configured analysis depth.
- **Keep the analysis.** Saved review entries retain the chart, move classifications, and analysis data. Open an older review and use **&#x21BB;** to analyze it again with the current rules and depth; the existing entry is updated instead of duplicated.
- **Continue a game later.** A live draft is saved every 20 seconds and when the app leaves the foreground. Named save slots let you keep, resume, overwrite, or delete separate games. PGN export preserves move notation and game metadata.
- **Bring your own positions.** Import PGN text or files, open a shared PGN from another app, enter a FEN, or use a board screenshot. Screenshot recognition uses templates and can learn from your corrections; check the pieces before continuing.
- **Study and practice.** Browse opening names and theory, train with Lichess puzzles filtered by rating and theme, and view accuracy trends and move-class distributions from your saved history.
- **Adjust the board.** Choose board and piece styles, sound themes, legal-move hints, analysis depth, and English or German text.

## The optional coach

The coach adds written explanations beside the engine analysis. You can use:

- **An on-device model:** download Gemma 3 1B or select a compatible GGUF model through the model-folder picker. The llama.cpp runner is included in the current build; model weights are downloaded separately.
- **Your own API:** supported routes include Anthropic, OpenAI, OpenRouter, Google Gemini, NVIDIA NIM, and a custom OpenAI-compatible endpoint such as LM Studio. Google and NVIDIA key detection checks the provider's model catalogue and builds a fallback route from the available configured models.
- **The deterministic fallback:** the option labeled Lichess produces comments from the engine and board facts without an LLM.

Model support and memory use depend on the phone and model. Coach explanations can be wrong; use the Stockfish lines to check them. Provider quotas and pricing belong to the provider, so a configured fallback does not guarantee free or available access.

## A quick first run

1. Start **New Game**, or use **Import** for a PGN, FEN, or screenshot.
2. To play against the engine, open **Setup Board - Play from here - VS Engine** and choose its side and strength.
3. Select **Analyze game**, then step through the moves. Tap a move card to return to its position and inspect the alternatives.
4. Open a saved review and select **&#x21BB;** when you want to refresh its analysis. Higher depths take longer.
5. Use **Save game** for a named playing slot and **Saved games** to continue it. Analyzed games also appear in the review history.
6. Open the settings drawer for appearance, language, engine settings, and the optional coach.

## What stays on your phone

Stockfish searches, game storage, and on-device coach inference run locally. There is no app account to create. The app can connect to Lichess for opening and puzzle data, to a model host for downloads, and to your selected API endpoint for coaching. API coaching sends the supplied position context to that endpoint.

API keys are stored in encrypted app preferences. Games and settings are stored in the app's private storage. Share/export actions create files you can send yourself.

## Limits worth knowing

- Move labels are this app's own classification, not a promise to reproduce Chess.com's labels. Engine depth, evaluation differences, and the classification rules can change the result. Calibration is ongoing.
- Reviews now search to the selected depth rather than stopping every position after a short time budget, and clear the engine hash between positions. This reduces one source of run-to-run variation but makes deeper reviews slower.
- Screenshot import is template-based. The TensorFlow Lite integration exists, but no trained piece-recognition model is shipped.
- Continuous analysis and local language models use CPU, battery, and memory. The displayed opponent ELO is a strength setting, not a measured rating.

## Build it yourself

The app targets **Android 7.0+** and currently builds for **arm64-v8a**. Use JDK 17 and an Android SDK with API 34, NDK, and CMake 3.22.1. Android Studio can install the required native build tools. The first build needs internet access.

On Windows / PowerShell:

```powershell
git clone https://github.com/NomeSame/chess-analysis-android.git
cd chess-analysis-android
.\gradlew.bat assembleDebug testDebugUnitTest
```

On macOS / Linux, use `./gradlew` for the same tasks.

- The build downloads Stockfish's large NNUE network into `app/src/main/res/raw`; the small network is already in the repository.
- `gradle.properties` currently enables the on-device coach with `aiCoachLlama=true`. CMake fetches the pinned llama.cpp tag **b5050**. For a faster build without that runner, add `-PaiCoachLlama=false`.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. `assembleRelease` produces a release APK; sign it with your own key before distributing it.
- With a connected device, `connectedDebugAndroidTest` runs the Android tests. Use a disposable test installation: saved-game tests create and clear their fixtures.

## How I work on it

I decide what the app should do, work through the behavior, and use AI coding assistants to implement it. A recent example: saved reviews could be opened but not refreshed after the move-rating rules changed. The reanalysis action now reruns Stockfish and replaces the old review without creating another history entry.

The source is here so you can inspect those decisions, reproduce the build, or change them. Tests cover chess rules, imports, save/restore behavior, and classification boundaries; device tests exercise the board, native engine, and review flow.

## License and credits

The project is published under **GPL-3.0-or-later**; see [LICENSE](LICENSE). Third-party components and downloaded models retain their own licenses and terms.

- **Stockfish 18:** chess engine and NNUE networks.
- **llama.cpp:** optional local language-model runner, MIT licensed.
- **Gemma 3:** separately downloaded Google model, subject to the Gemma Terms of Use.
- **Lichess:** opening and puzzle data.

<!-- Screenshot candidates, once current captures are available:
1. Board and engine play - docs/board.png
2. Saved game review and reanalysis - docs/analysis.png
3. Coach settings and explanations - docs/coach.png
No screenshot files are claimed or linked until they exist.
-->
