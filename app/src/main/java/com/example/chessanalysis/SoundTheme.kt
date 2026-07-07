package com.example.chessanalysis

/**
 * Selectable piece-sound sets. Resource names in res/raw = [prefix] + {move,capture,castle,check}.
 * A missing file (e.g. no dedicated castle/check sound) falls back to the theme's move sound — see
 * MainActivity.loadSoundTheme().
 *
 * Both sets are our OWN synthesized audio (no third-party license / no attribution): CLASSIC = simple
 * beeps, WOOD = synthesized wooden-knock set (move/capture/castle/check). The earlier Lichess
 * "standard"/"piano" sets were removed as non-releasable (non-free / AGPL — see status.md).
 */
enum class SoundTheme(val id: String, val labelRes: Int, val prefix: String) {
    CLASSIC("classic", R.string.sound_theme_classic, ""),       // move/capture/castle/check (synth beeps, own)
    WOOD("wood", R.string.sound_theme_wood, "wood_"),           // light derivation of the reference set
    TEST("test", R.string.sound_theme_test, "test_"),           // heavy variant (pitch-shift + synth blend + sat)
    TEST_SYNTH("testsynth", R.string.sound_theme_test_synth, "testsynth_"); // 70% own synth + 30% pitched original

    companion object {
        fun byId(id: String?): SoundTheme = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}
