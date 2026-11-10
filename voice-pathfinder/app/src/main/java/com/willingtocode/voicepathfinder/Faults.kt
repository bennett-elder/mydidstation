package com.willingtocode.voicepathfinder

/**
 * The most useful thing a thrown fault has to say.
 *
 * Shared rather than private to one class, because the log is the only diagnostic channel
 * this app has in every profile, and every part that reports a failure should say the same
 * thing about it.
 */
internal fun describe(fault: Throwable): String =
    fault.message?.takeIf { it.isNotBlank() } ?: fault.javaClass.simpleName
