package org.fossify.phone.voice

import org.junit.Test

class VoiceCoreTest {
    @Test fun passthroughIsBitExact() = VoiceCoreChecks.passthroughIsBitExact()
    @Test fun silenceIsImmediateAndExact() = VoiceCoreChecks.silenceIsImmediateAndExact()
    @Test fun respectsCountAndRejectsInvalidLengths() = VoiceCoreChecks.respectsCountAndRejectsInvalidLengths()
    @Test fun validatesRates() = VoiceCoreChecks.validatesRates()
    @Test fun girlPresetActuallyRaisesPitch() = VoiceCoreChecks.girlPresetActuallyRaisesPitch()
    @Test fun chipmunkPresetActuallyRaisesPitch() = VoiceCoreChecks.chipmunkPresetActuallyRaisesPitch()
    @Test fun boyPresetActuallyLowersPitch() = VoiceCoreChecks.boyPresetActuallyLowersPitch()
    @Test fun demoMenuHasExactlyTheFourRequestedPresets() = VoiceCoreChecks.demoMenuHasExactlyTheFourRequestedPresets()
    @Test fun robotProducesRingModulationSidebands() = VoiceCoreChecks.robotProducesRingModulationSidebands()
    @Test fun resultsDoNotDependOnReadChunkSize() = VoiceCoreChecks.resultsDoNotDependOnReadChunkSize()
    @Test fun switchingPreservesLengthBoundsAndEventuallyDrainsHistory() = VoiceCoreChecks.switchingPreservesLengthBoundsAndEventuallyDrainsHistory()
    @Test fun partialWritesPreserveEverySampleAndTransformOnce() = VoiceCoreChecks.partialWritesPreserveEverySampleAndTransformOnce()
    @Test fun zeroWriteNeverReadsOverPendingFrame() = VoiceCoreChecks.zeroWriteNeverReadsOverPendingFrame()
    @Test fun noDataReturnsWithoutBusySpinningThenTimesOut() = VoiceCoreChecks.noDataReturnsWithoutBusySpinningThenTimesOut()
    @Test fun badReadResultsFailClosed() = VoiceCoreChecks.badReadResultsFailClosed()
    @Test fun badWriteResultsFailClosed() = VoiceCoreChecks.badWriteResultsFailClosed()
    @Test fun digitalSilenceCountsAsProgress() = VoiceCoreChecks.digitalSilenceCountsAsProgress()
    @Test fun rejectsInvalidPipeConfiguration() = VoiceCoreChecks.rejectsInvalidPipeConfiguration()
    @Test fun closesModeExactlyOnce() = VoiceCoreChecks.closesModeExactlyOnce()
    @Test fun cancellationBeforeStartupNeverRequestsMode() = VoiceCoreChecks.cancellationBeforeStartupNeverRequestsMode()
    @Test fun partlyFailedModeEntryStillWithdraws() = VoiceCoreChecks.partlyFailedModeEntryStillWithdraws()
    @Test fun modeReleaseFailureIsNotSwallowed() = VoiceCoreChecks.modeReleaseFailureIsNotSwallowed()
    @Test fun failedWithdrawalCanBeRetriedWithoutReenteringMode() = VoiceCoreChecks.failedWithdrawalCanBeRetriedWithoutReenteringMode()
    @Test fun cancellationRacingModeEntryWithdrawsAfterEntry() = VoiceCoreChecks.cancellationRacingModeEntryWithdrawsAfterEntry()
    @Test fun silenceTrialHasFiveSecondLimit() = VoiceCoreChecks.silenceTrialHasFiveSecondLimit()
    @Test fun switchingDoesNotExtendTrialDeadline() = VoiceCoreChecks.switchingDoesNotExtendTrialDeadline()
}
