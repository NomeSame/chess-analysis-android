package com.example.chessanalysis.engine

import kotlin.jvm.Volatile

object EngineHolder {
    val engine = StockfishEngine()
    val analyzer = LiveAnalyzer(engine)
    @Volatile var ready = false
}
