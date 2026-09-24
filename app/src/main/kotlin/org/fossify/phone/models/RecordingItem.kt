package org.fossify.phone.models

import java.io.File

data class RecordingItem(
    val file: File,
    val durationMs: Long
)
