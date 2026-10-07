package com.example.chessanalysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chessanalysis.engine.StockfishEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual JNI queues/streams; a second queued quit broke the next UCI handshake. */
@RunWith(AndroidJUnit4::class)
class EngineRestartDeviceTest {
    @Test fun engineCanRestartRepeatedlyInTheSameProcess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = StockfishEngine()
        repeat(3) { cycle ->
            try {
                assertTrue("Native engine initialization failed in cycle $cycle", engine.init(context))
                assertTrue("NNUE networks missing in cycle $cycle", engine.verifyNetworks())
            } finally {
                engine.shutdown()
            }
        }
    }
}
