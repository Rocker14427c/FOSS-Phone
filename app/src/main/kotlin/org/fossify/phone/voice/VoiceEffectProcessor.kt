package org.fossify.phone.voice

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** OFF is deliberately not a DSP mode: Off must tear down call redirection altogether. */
enum class VoiceEffect(val semitones: Double = 0.0) {
    SILENCE_CHECK, PASSTHROUGH, GIRL(5.0), BOY(-4.0), ROBOT, CHIPMUNK(9.0);

    companion object {
        val DEMO_PRESETS = listOf(GIRL, BOY, ROBOT, CHIPMUNK)
    }
}

/**
 * Mono PCM16, fixed input/output length, no allocations in process().
 *
 * Pitch core adapted from Paul Batchelor's Soundpipe modules/pshift.c at
 * 3efb43bdabd0ed23b17c694292b5a79f1692a3ea (MIT). See assets/licenses/Soundpipe-MIT.txt.
 * Replaces the generated C's 65536-sample buffer with a sample-rate-sized ring, retaining
 * its two interpolated delay taps and crossfade at the delay wrap. This is a lightweight
 * pitch effect, NOT formant-preserving or neural gender/identity conversion.
 */
class VoiceEffectProcessor(private val sampleRate: Int = 16_000) {
    init { require(sampleRate in 8_000..48_000) }

    private val window = sampleRate * 0.040 // 40ms; two taps need at most 80ms of history.
    private val crossfade = sampleRate * 0.010
    private val ring = FloatArray(Integer.highestOneBit((2 * window).toInt() + 2) shl 1)
    private val mask = ring.size - 1
    private val carrier = FloatArray(1024) { sin(2.0 * PI * it / 1024).toFloat() }
    private val carrierStep = 90.0 * carrier.size / sampleRate
    private var carrierPhase = 0.0
    private var writeIndex = 0
    private var delay = 0.0
    private var ratio = 1.0
    private var effect = VoiceEffect.PASSTHROUGH
    private var previousOutput = 0.0
    private var transitionFrom = 0.0
    private var transitionLeft = 0
    private val transitionLength = sampleRate / 200 // 5ms click-reduction ramp when switching.

    /** Worker-thread only; never change DSP state concurrently with process(). */
    fun select(effect: VoiceEffect) {
        if (this.effect == effect) return
        this.effect = effect
        ratio = 2.0.pow(effect.semitones / 12.0)
        transitionFrom = previousOutput
        transitionLeft = transitionLength
    }

    fun process(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        for (i in 0 until count) {
            val input = samples[i].toFloat()
            ring[writeIndex] = input
            var output = when (effect) {
                VoiceEffect.SILENCE_CHECK -> 0.0 // Never fades: this must be digital silence.
                VoiceEffect.PASSTHROUGH -> input.toDouble()
                VoiceEffect.ROBOT -> input * carrier[carrierPhase.toInt()].toDouble() * 0.8
                VoiceEffect.GIRL, VoiceEffect.BOY, VoiceEffect.CHIPMUNK -> {
                    delay = (delay + window + 1.0 - ratio) % window
                    val mix = min(delay / crossfade, 1.0)
                    (tap(delay) * mix + tap(delay + window) * (1.0 - mix)) * 0.9
                }
            }
            if (transitionLeft > 0) {
                if (effect != VoiceEffect.SILENCE_CHECK) {
                    val mix = 1.0 - transitionLeft.toDouble() / transitionLength
                    output = transitionFrom * (1.0 - mix) + output * mix
                }
                transitionLeft--
            }
            samples[i] = output.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            previousOutput = samples[i].toDouble()
            writeIndex = (writeIndex + 1) and mask
            carrierPhase = (carrierPhase + carrierStep) % carrier.size
        }
    }

    private fun tap(distance: Double): Double {
        val whole = distance.toInt()
        val fraction = distance - whole
        return ring[(writeIndex - whole) and mask] * (1.0 - fraction) +
            ring[(writeIndex - whole - 1) and mask] * fraction
    }
}
