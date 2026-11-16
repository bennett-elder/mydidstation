package com.willingtocode.mydidstation

/** Counters for the call, refreshed four times a second rather than per packet. */
data class MediaStats(
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val lost: Long = 0,
    val duplicates: Long = 0,
    val late: Long = 0,
    val reordered: Long = 0,
    val held: Int = 0,
    val sendLevel: Float = 0f,
    val receiveLevel: Float = 0f,
    val localRtpPort: Int = 0,
    val remoteMedia: String = "",
    val echoCancelled: Boolean = false,
    val speaker: Boolean = false,
)
