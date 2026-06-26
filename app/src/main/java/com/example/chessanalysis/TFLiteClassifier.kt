package com.example.chessanalysis

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteClassifier(context: Context) {

    companion object {
        private const val MODEL_PATH = "models/chess_cnn.tflite"
        private const val INPUT_SIZE = 64
    }

    private var interpreter: Interpreter? = null

    init {
        try {
            val model = loadModelFile(context)
            interpreter = Interpreter(model)
        } catch (_: Exception) {
            interpreter = null
        }
    }

    fun isAvailable(): Boolean = interpreter != null

    fun classifyCell(cellBitmap: Bitmap): Pair<Int, Float> {
        val resized = Bitmap.createScaledBitmap(cellBitmap, INPUT_SIZE, INPUT_SIZE, true)
        val input = convertBitmapToByteBuffer(resized)
        if (resized != cellBitmap) resized.recycle()

        val output = Array(1) { FloatArray(13) }
        interpreter?.run(input, output)

        val probs = output[0]
        var bestIdx = 0
        var bestProb = probs[0]
        for (i in 1 until 13) {
            if (probs[i] > bestProb) {
                bestProb = probs[i]
                bestIdx = i
            }
        }
        return bestIdx to bestProb
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val afd = context.assets.openFd(MODEL_PATH)
        val inputStream = FileInputStream(afd.fileDescriptor)
        val fc = inputStream.channel
        val startOffset = afd.startOffset
        val declaredLength = afd.declaredLength
        return fc.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 127.5f - 1.0f
            val g = ((pixel shr 8) and 0xFF) / 127.5f - 1.0f
            val b = (pixel and 0xFF) / 127.5f - 1.0f
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
        buffer.rewind()
        return buffer
    }
}
