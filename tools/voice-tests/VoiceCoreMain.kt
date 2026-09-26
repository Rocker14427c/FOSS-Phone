import org.fossify.phone.voice.VoiceCoreChecks

/** SDK-free runner. JUnit in :app:testFossDebugUnitTest invokes the same checks. */
fun main() {
    val checks = listOf(
        "passthroughIsBitExact" to VoiceCoreChecks::passthroughIsBitExact,
        "silenceIsImmediateAndExact" to VoiceCoreChecks::silenceIsImmediateAndExact,
        "respectsCountAndRejectsInvalidLengths" to VoiceCoreChecks::respectsCountAndRejectsInvalidLengths,
        "validatesRates" to VoiceCoreChecks::validatesRates,
        "girlPresetActuallyRaisesPitch" to VoiceCoreChecks::girlPresetActuallyRaisesPitch,
        "chipmunkPresetActuallyRaisesPitch" to VoiceCoreChecks::chipmunkPresetActuallyRaisesPitch,
        "boyPresetActuallyLowersPitch" to VoiceCoreChecks::boyPresetActuallyLowersPitch,
        "demoMenuHasExactlyTheFourRequestedPresets" to VoiceCoreChecks::demoMenuHasExactlyTheFourRequestedPresets,
        "robotProducesRingModulationSidebands" to VoiceCoreChecks::robotProducesRingModulationSidebands,
        "resultsDoNotDependOnReadChunkSize" to VoiceCoreChecks::resultsDoNotDependOnReadChunkSize,
        "switchingPreservesLengthBoundsAndEventuallyDrainsHistory" to VoiceCoreChecks::switchingPreservesLengthBoundsAndEventuallyDrainsHistory,
        "partialWritesPreserveEverySampleAndTransformOnce" to VoiceCoreChecks::partialWritesPreserveEverySampleAndTransformOnce,
        "zeroWriteNeverReadsOverPendingFrame" to VoiceCoreChecks::zeroWriteNeverReadsOverPendingFrame,
        "noDataReturnsWithoutBusySpinningThenTimesOut" to VoiceCoreChecks::noDataReturnsWithoutBusySpinningThenTimesOut,
        "badReadResultsFailClosed" to VoiceCoreChecks::badReadResultsFailClosed,
        "badWriteResultsFailClosed" to VoiceCoreChecks::badWriteResultsFailClosed,
        "digitalSilenceCountsAsProgress" to VoiceCoreChecks::digitalSilenceCountsAsProgress,
        "rejectsInvalidPipeConfiguration" to VoiceCoreChecks::rejectsInvalidPipeConfiguration,
        "closesModeExactlyOnce" to VoiceCoreChecks::closesModeExactlyOnce,
        "cancellationBeforeStartupNeverRequestsMode" to VoiceCoreChecks::cancellationBeforeStartupNeverRequestsMode,
        "partlyFailedModeEntryStillWithdraws" to VoiceCoreChecks::partlyFailedModeEntryStillWithdraws,
        "modeReleaseFailureIsNotSwallowed" to VoiceCoreChecks::modeReleaseFailureIsNotSwallowed,
        "failedWithdrawalCanBeRetriedWithoutReenteringMode" to VoiceCoreChecks::failedWithdrawalCanBeRetriedWithoutReenteringMode,
        "cancellationRacingModeEntryWithdrawsAfterEntry" to VoiceCoreChecks::cancellationRacingModeEntryWithdrawsAfterEntry,
        "silenceTrialHasFiveSecondLimit" to VoiceCoreChecks::silenceTrialHasFiveSecondLimit,
        "switchingDoesNotExtendTrialDeadline" to VoiceCoreChecks::switchingDoesNotExtendTrialDeadline
    )
    for ((name, test) in checks) {
        test()
        println("PASS $name")
    }
    println("${checks.size} voice core checks passed")
}
