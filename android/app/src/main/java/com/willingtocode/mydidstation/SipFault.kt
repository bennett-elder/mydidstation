package com.willingtocode.mydidstation

/**
 * A SIP exchange that failed, with a sentence that is worth reading.
 *
 * The message is what reaches the log and the screen, so it says what happened rather than
 * naming a class: "no answer to INVITE after 32 s" is a diagnosis, an exception type is not.
 */
internal class SipFault(message: String) : Exception(message)
