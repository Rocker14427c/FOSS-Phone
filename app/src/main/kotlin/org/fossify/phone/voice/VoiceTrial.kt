package org.fossify.phone.voice

/** No persisted 'device supported' flag: port discovery cannot prove dry-mic suppression. */
class VoiceTrial(startedAtMs: Long, initialEffect: VoiceEffect) {
    private val deadlineMs = startedAtMs + if (initialEffect == VoiceEffect.SILENCE_CHECK) 5_000 else 30_000
    var effect = initialEffect
        private set

    fun select(next: VoiceEffect) {
        // Silence is a separate, deliberately short test, never entered or escaped mid-trial.
        require(effect != VoiceEffect.SILENCE_CHECK && next != VoiceEffect.SILENCE_CHECK)
        effect = next
    }

    fun isExpired(nowMs: Long): Boolean = nowMs >= deadlineMs
}
