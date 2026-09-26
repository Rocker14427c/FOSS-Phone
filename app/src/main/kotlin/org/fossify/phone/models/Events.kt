package org.fossify.phone.models

sealed class Events {
    data object RefreshCallLog : Events()
    data object RecordingStateChanged : Events()
    data object VoiceEffectStateChanged : Events()
}
