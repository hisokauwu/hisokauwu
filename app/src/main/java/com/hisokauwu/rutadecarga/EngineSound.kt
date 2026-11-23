package com.hisokauwu.rutadecarga

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.hisokauwu.rutadecarga.core.Truck
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Sonido de motor diésel sintetizado en tiempo real a partir de las rpm y el acelerador. */
class EngineSound(private val truck: Truck) {
    private val rate = 22050
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "engine-sound").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }
        track.play()
        val buf = ShortArray(512)
        val rnd = Random(1)
        var phase = 0.0
        var lp = 0.0
        var lp2 = 0.0
        var freq = 30.0
        var vol = 0.2
        while (running) {
            // 6 cilindros, 4 tiempos: 3 explosiones por vuelta
            val targetFreq = truck.rpm / 60.0 * 3.0
            val targetVol = 0.18 + 0.32 * truck.throttleApplied + 0.1 * (truck.rpm / Truck.MAX_RPM)
            val rumble = if (!truck.onRoad && abs(truck.speed) > 1f) 0.25 else 0.0
            for (i in buf.indices) {
                freq += (targetFreq - freq) * 0.002
                vol += (targetVol - vol) * 0.001
                phase += freq / rate
                if (phase >= 1.0) phase -= 1.0
                // Pulso de explosión + armónicos + ruido
                val pulse = if (phase < 0.18) sin(phase / 0.18 * PI) else 0.0
                val tone = 0.55 * pulse + 0.25 * sin(2 * PI * phase * 0.5) + 0.15 * sin(2 * PI * phase * 2)
                val noise = (rnd.nextDouble() - 0.5) * (0.25 + rumble)
                val raw = tone + noise * (0.3 + 0.5 * truck.throttleApplied)
                lp += (raw - lp) * 0.22
                lp2 += (lp - lp2) * 0.35
                buf[i] = (lp2 * vol * 26000).toInt().coerceIn(-32767, 32767).toShort()
            }
            track.write(buf, 0, buf.size)
        }
        track.stop()
        track.release()
    }
}
