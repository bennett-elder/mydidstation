package com.willingtocode.mydidstation

sealed interface PathState {
    data object Unregistered : PathState
    data class Registering(val attempt: Int) : PathState
    data class Registered(val grantedSeconds: Int, val at: Long) : PathState
    data class RegistrationFailed(val reason: String) : PathState
    data class Dialing(val number: String) : PathState
    data class Ringing(val number: String) : PathState
    data class Connected(
        val number: String,
        val codec: String,
        val encrypted: Boolean,
        val audio: Boolean,
        val at: Long,
    ) : PathState
    data class Ended(val reason: String) : PathState
    data class Failed(val reason: String) : PathState
}
