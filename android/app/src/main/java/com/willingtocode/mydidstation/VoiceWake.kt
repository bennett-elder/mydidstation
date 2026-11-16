package com.willingtocode.mydidstation

/**
 * Where a wake finds the lines.
 *
 * A receiver is started by the system with nothing but a context, so it has to reach whatever
 * owns the registrations. The voice module did that by casting the application to its own
 * class, which works when there is one such class and stops working the moment the engine
 * lives inside an app that has its own application object. This is the same handover without
 * the cast: the owner installs these once, and a receiver calls them.
 *
 * Both default to doing nothing, which is the correct answer before the engine exists.
 */
internal object VoiceWake {
    /** Re-register one line, named by its sub-account. */
    @Volatile
    var refreshLine: (String) -> Unit = {}

    /** Check every line whose grant is close to running out. */
    @Volatile
    var refreshDueLines: () -> Unit = {}
}
