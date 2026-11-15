package io.github.mangi.eta.agent.voice

import kotlin.math.sqrt

/** Conservative energy VAD, not a neural speech/noise classifier. PCM16 LE, mono, 16 kHz.
 * Uses the sample clock, never wall time/network latency. No audio is retained or modified.
 */
internal class SpeechEndpointDetector(private val silenceMs: Int) {
    private val windowSamples = 320 // 20 ms, independent of capture frame boundaries
    private val calibration = ArrayList<Double>(10)
    private var noiseFloor = 40.0
    private var energy = 0.0
    private var samples = 0
    private var lowByte = -1
    private var voiceSamples = 0L
    private var silenceSamples = 0L
    private var armed = false
    private var ended = false

    fun accept(pcm: ByteArray): Boolean {
        if (silenceMs <= 0 || ended) return false
        var triggered = false
        for (byte in pcm) {
            if (lowByte < 0) { lowByte = byte.toInt() and 255; continue }
            val sample = (lowByte or (byte.toInt() shl 8)).toShort().toDouble()
            lowByte = -1
            energy += sample * sample
            if (++samples == windowSamples) {
                val rms = sqrt(energy / samples)
                samples = 0
                energy = 0.0
                if (calibration.size < 10) {
                    calibration.add(rms)
                    if (calibration.size == 10) {
                        // Bounded startup calibration: do not learn immediate loud speech as noise.
                        // Replay energies (not PCM) so speech at capture start still counts.
                        noiseFloor = calibration.minOrNull()!!.coerceIn(40.0, 600.0)
                        for (value in calibration) triggered = observe(value) || triggered
                    }
                } else {
                    triggered = observe(rms) || triggered
                }
            }
        }
        return triggered
    }

    private fun observe(rms: Double): Boolean {
        if (ended) return false
        val high = maxOf(220.0, noiseFloor * 2.6)
        val low = maxOf(140.0, noiseFloor * 1.6)
        if (rms >= high) {
            voiceSamples += windowSamples
            if (voiceSamples >= 3_200) armed = true // 200 ms rejects clicks
            silenceSamples = 0
        } else if (rms < low) {
            if (!armed) voiceSamples = 0
            noiseFloor = (noiseFloor * 0.95 + rms * 0.05).coerceIn(40.0, 600.0)
            if (armed) silenceSamples += windowSamples
        } else {
            // Hysteresis: uncertain sound is not a safe trailing pause.
            if (!armed) voiceSamples = 0
            silenceSamples = 0
        }
        if (armed && silenceSamples >= silenceMs.toLong() * 16) {
            ended = true
            return true
        }
        return false
    }
}
