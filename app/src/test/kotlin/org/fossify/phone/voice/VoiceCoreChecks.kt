package org.fossify.phone.voice

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Shared by JUnit and the SDK-free test runner; these exercise the actual production classes. */
object VoiceCoreChecks {
    private fun mustFail(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        error("Expected operation to fail")
    }
    private fun wave(size: Int = 16_000, hz: Double = 440.0) =
        ShortArray(size) { (12_000 * sin(2.0 * PI * hz * it / 16_000)).toInt().toShort() }

    fun passthroughIsBitExact() {
        val input = ShortArray(65_536) { (it + Short.MIN_VALUE).toShort() }
        val output = input.clone()
        VoiceEffectProcessor().process(output, output.size)
        check(input.contentEquals(output))
    }

    fun silenceIsImmediateAndExact() {
        val dsp = VoiceEffectProcessor()
        val data = wave(320)
        dsp.select(VoiceEffect.ROBOT)
        dsp.process(data, data.size)
        data.fill(Short.MAX_VALUE)
        dsp.select(VoiceEffect.SILENCE_CHECK)
        dsp.process(data, data.size)
        check(data.all { it == 0.toShort() })
    }

    fun respectsCountAndRejectsInvalidLengths() {
        val dsp = VoiceEffectProcessor()
        dsp.select(VoiceEffect.SILENCE_CHECK)
        val data = ShortArray(10) { 123 }
        dsp.process(data, 4)
        check(data.take(4).all { it == 0.toShort() })
        check(data.drop(4).all { it == 123.toShort() })
        dsp.process(data, 0)
        mustFail { dsp.process(data, -1) }
        mustFail { dsp.process(data, 11) }
    }

    fun validatesRates() {
        mustFail { VoiceEffectProcessor(0) }
        mustFail { VoiceEffectProcessor(7_999) }
        mustFail { VoiceEffectProcessor(48_001) }
        for (rate in listOf(8_000, 16_000, 44_100, 48_000)) {
            val dsp = VoiceEffectProcessor(rate)
            dsp.select(VoiceEffect.CHIPMUNK)
            val silence = ShortArray(rate / 10)
            dsp.process(silence, silence.size)
            check(silence.all { it == 0.toShort() })
        }
    }

    private fun power(data: ShortArray, hz: Double): Double {
        val coefficient = 2.0 * cos(2.0 * PI * hz / 16_000)
        var one = 0.0
        var two = 0.0
        for (i in 3_200 until data.size) { // Exclude delay startup and selection ramp.
            val next = data[i] + coefficient * one - two
            two = one
            one = next
        }
        return one * one + two * two - coefficient * one * two
    }
    private fun dominant(data: ShortArray): Int = (200..1000 step 2).maxBy { power(data, it.toDouble()) }
    private fun changed(effect: VoiceEffect) = wave().also { data ->
        VoiceEffectProcessor().apply { select(effect); process(data, data.size) }
    }

    fun girlPresetActuallyRaisesPitch() {
        val output = changed(VoiceEffect.GIRL)
        val peak = dominant(output)
        check(abs(peak - 587.3) < 20) { "Higher peak was $peak Hz" }
        check(power(output, peak.toDouble()) > power(output, 440.0) * 10)
    }

    fun chipmunkPresetActuallyRaisesPitch() {
        val output = changed(VoiceEffect.CHIPMUNK)
        val peak = dominant(output)
        check(abs(peak - 740.0) < 20) { "Child peak was $peak Hz" }
        check(power(output, peak.toDouble()) > power(output, 440.0) * 10)
    }

    fun boyPresetActuallyLowersPitch() {
        val output = changed(VoiceEffect.BOY)
        val peak = dominant(output)
        check(abs(peak - 349.2) < 20) { "Boy preset peak was $peak Hz" }
        check(power(output, peak.toDouble()) > power(output, 440.0) * 10)
    }

    fun demoMenuHasExactlyTheFourRequestedPresets() {
        check(VoiceEffect.DEMO_PRESETS == listOf(
            VoiceEffect.GIRL, VoiceEffect.BOY, VoiceEffect.ROBOT, VoiceEffect.CHIPMUNK))
    }

    fun robotProducesRingModulationSidebands() {
        val output = changed(VoiceEffect.ROBOT)
        check(power(output, 350.0) > power(output, 440.0) * 100)
        check(power(output, 530.0) > power(output, 440.0) * 100)
    }

    fun resultsDoNotDependOnReadChunkSize() {
        for (effect in VoiceEffect.entries) {
            val whole = wave()
            VoiceEffectProcessor().apply { select(effect); process(whole, whole.size) }
            val chunks = wave()
            val dsp = VoiceEffectProcessor().apply { select(effect) }
            var offset = 0
            while (offset < chunks.size) {
                val end = minOf(chunks.size, offset + 137)
                val block = chunks.copyOfRange(offset, end)
                dsp.process(block, block.size)
                block.copyInto(chunks, offset)
                offset = end
            }
            check(whole.contentEquals(chunks)) { "Chunk-dependent output for $effect" }
        }
    }

    fun switchingPreservesLengthBoundsAndEventuallyDrainsHistory() {
        val dsp = VoiceEffectProcessor()
        for (effect in VoiceEffect.entries) {
            dsp.select(effect)
            val data = wave(4_000)
            dsp.process(data, data.size)
            check(data.size == 4_000 && data.all { abs(it.toInt()) <= 12_000 })
        }
        dsp.select(VoiceEffect.CHIPMUNK)
        val silence = ShortArray(4_000)
        dsp.process(silence, silence.size)
        check(silence.takeLast(1_600).all { it == 0.toShort() })
    }

    fun partialWritesPreserveEverySampleAndTransformOnce() {
        var reads = 0
        var transforms = 0
        val output = mutableListOf<Short>()
        val pipe = PcmPipe(4, read = {
            reads++
            for (i in it.indices) it[i] = (i + 1).toShort()
            4
        }, write = { data, offset, count ->
            val size = minOf(2, count)
            output.addAll(data.slice(offset until offset + size))
            size
        }, transform = { data, count ->
            transforms++
            for (i in 0 until count) data[i] = (data[i] * 2).toShort()
        }, startedAtMs = 0)
        pipe.step(0)
        pipe.step(10)
        check(output == listOf<Short>(2, 4, 6, 8) && reads == 1 && transforms == 1)
    }

    fun zeroWriteNeverReadsOverPendingFrame() {
        var reads = 0
        var writes = 0
        val pipe = PcmPipe(4, read = { reads++; 4 }, write = { _, _, _ -> writes++; 0 }, startedAtMs = 0)
        pipe.step(0); pipe.step(5); pipe.step(10)
        check(reads == 1 && writes == 3)
        mustFail { pipe.step(1_000) }
    }

    fun noDataReturnsWithoutBusySpinningThenTimesOut() {
        var reads = 0
        val pipe = PcmPipe(4, read = { reads++; 0 }, write = { _, _, _ -> error("Unexpected write") }, startedAtMs = 10)
        pipe.step(10); pipe.step(20)
        check(reads == 2)
        mustFail { pipe.step(1_010) }
    }

    fun badReadResultsFailClosed() {
        for (value in listOf(-1, -6, 5)) {
            val pipe = PcmPipe(4, read = { value }, write = { _, _, _ -> error("Unexpected write") }, startedAtMs = 0)
            mustFail { pipe.step(0) }
        }
    }

    fun badWriteResultsFailClosed() {
        for (value in listOf(-1, -6, 5)) {
            val pipe = PcmPipe(4, read = { 4 }, write = { _, _, _ -> value }, startedAtMs = 0)
            mustFail { pipe.step(0) }
        }
    }

    fun digitalSilenceCountsAsProgress() {
        val pipe = PcmPipe(4, read = { it.fill(0); 4 }, write = { data, _, count ->
            check(data.all { it == 0.toShort() }); count
        }, startedAtMs = 0)
        for (time in 0L..5_000L step 500) pipe.step(time)
    }

    fun rejectsInvalidPipeConfiguration() {
        mustFail { PcmPipe(0, { 0 }, { _, _, _ -> 0 }, startedAtMs = 0) }
        mustFail { PcmPipe(4, { 0 }, { _, _, _ -> 0 }, startedAtMs = 0, stallTimeoutMs = 0) }
    }

    fun closesModeExactlyOnce() {
        var entered = 0
        var withdrawn = 0
        val lease = AudioModeLease({ entered++ }, { withdrawn++ })
        check(lease.acquire())
        lease.close(); lease.close()
        check(entered == 1 && withdrawn == 1 && !lease.acquire())
    }

    fun cancellationBeforeStartupNeverRequestsMode() {
        val lease = AudioModeLease({ error("Unexpected request") }, { error("Unexpected withdrawal") })
        lease.close()
        check(!lease.acquire())
    }

    fun partlyFailedModeEntryStillWithdraws() {
        var withdrawn = false
        val lease = AudioModeLease({ error("Vendor rejected mode after receiving request") }, { withdrawn = true })
        mustFail { lease.acquire() }
        lease.close()
        check(withdrawn)
    }

    fun modeReleaseFailureIsNotSwallowed() {
        val lease = AudioModeLease({}, { error("AudioService died") })
        lease.acquire()
        mustFail { lease.close() }
        mustFail { lease.close() }
        check(!lease.acquire())
    }

    fun failedWithdrawalCanBeRetriedWithoutReenteringMode() {
        var attempts = 0
        val lease = AudioModeLease({}, {
            attempts++
            check(attempts > 1) { "Transient withdrawal failure" }
        })
        lease.acquire()
        mustFail { lease.close() }
        check(!lease.acquire())
        lease.close(); lease.close()
        check(attempts == 2)
    }

    fun cancellationRacingModeEntryWithdrawsAfterEntry() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val entering = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val lease = AudioModeLease({
            calls.add("enter")
            entering.countDown()
            check(proceed.await(2, TimeUnit.SECONDS))
        }, { calls.add("withdraw") })
        val worker = Thread { lease.acquire() }.apply { isDaemon = true; start() }
        check(entering.await(2, TimeUnit.SECONDS))
        val stopper = Thread { lease.close(); finished.countDown() }.apply { isDaemon = true; start() }
        proceed.countDown()
        check(finished.await(2, TimeUnit.SECONDS))
        worker.join(1_000); stopper.join(1_000)
        check(calls == listOf("enter", "withdraw"))
    }

    fun silenceTrialHasFiveSecondLimit() {
        val trial = VoiceTrial(100, VoiceEffect.SILENCE_CHECK)
        check(!trial.isExpired(5_099) && trial.isExpired(5_100))
        mustFail { trial.select(VoiceEffect.ROBOT) }
    }

    fun switchingDoesNotExtendTrialDeadline() {
        val trial = VoiceTrial(100, VoiceEffect.GIRL)
        trial.select(VoiceEffect.CHIPMUNK)
        trial.select(VoiceEffect.ROBOT)
        check(trial.effect == VoiceEffect.ROBOT)
        check(!trial.isExpired(30_099) && trial.isExpired(30_100))
        mustFail { trial.select(VoiceEffect.SILENCE_CHECK) }
    }
}
