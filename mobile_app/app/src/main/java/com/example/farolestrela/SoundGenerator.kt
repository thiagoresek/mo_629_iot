package com.example.farolestrela

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sin
import kotlin.math.PI

object SoundGenerator {
    fun getOrCreateAlarmSoundFile(context: Context): File {
        val file = File(context.filesDir, "emergency_alarm.wav")
        if (!file.exists()) {
            try {
                val sampleRate = 44100
                val duration = 1.5 // seconds
                val numSamples = (sampleRate * duration).toInt()
                val dataSize = numSamples * 2 // 16-bit

                val pcmData = ByteArray(dataSize)
                val freq1 = 800.0
                val freq2 = 1200.0

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val freq = if ((t * 4).toInt() % 2 == 0) freq1 else freq2
                    val sample = (sin(2 * PI * freq * t) * 32767 * 0.9).toInt().toShort()

                    // Write 16-bit little endian
                    pcmData[i * 2] = (sample.toInt() and 0xff).toByte()
                    pcmData[i * 2 + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
                }

                val totalSize = 36 + dataSize

                FileOutputStream(file).use { fos ->
                    // RIFF header
                    fos.write("RIFF".toByteArray())
                    fos.write(intToByteArray(totalSize), 0, 4)
                    fos.write("WAVE".toByteArray())
                    // fmt chunk
                    fos.write("fmt ".toByteArray())
                    fos.write(intToByteArray(16), 0, 4) // Subchunk1Size (16 for PCM)
                    fos.write(shortToByteArray(1), 0, 2)  // AudioFormat (1 for PCM)
                    fos.write(shortToByteArray(1), 0, 2)  // NumChannels (1 for Mono)
                    fos.write(intToByteArray(sampleRate), 0, 4) // SampleRate
                    fos.write(intToByteArray(sampleRate * 2), 0, 4) // ByteRate
                    fos.write(shortToByteArray(2), 0, 2)  // BlockAlign
                    fos.write(shortToByteArray(16), 0, 2) // BitsPerSample
                    // data chunk
                    fos.write("data".toByteArray())
                    fos.write(intToByteArray(dataSize), 0, 4)
                    fos.write(pcmData)
                }
            } catch (e: Exception) {
                // Fallback
            }
        }
        return file
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xff).toByte(),
            ((value.toInt() shr 8) and 0xff).toByte()
        )
    }
}
