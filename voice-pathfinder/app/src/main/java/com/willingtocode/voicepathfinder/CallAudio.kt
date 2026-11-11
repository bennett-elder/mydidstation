package com.willingtocode.voicepathfinder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build

/**
 * Twenty-millisecond PCM in and out of the platform, and nothing else.
 *
 * This is the phase's riskiest file and the reason it exists in this shape: the plan's
 * third failure condition is that acceptable audio needs a native stack. Whether that
 * is true is decided here, by `AudioRecord` and `AudioTrack` on one handset, not by an
 * architecture diagram.
 *
 * Choices worth naming, because each one produces a specific kind of bad audio when
 * wrong:
 *
 * - `VOICE_COMMUNICATION` as the source, not `MIC`. It is the tuned path with the
 *   platform's echo cancellation and gain in front of it, and it selects the same
 *   microphone a real call uses.
 * - `MODE_IN_COMMUNICATION` for the audio session, so routing and volume follow call
 *   rules rather than media rules.
 * - AEC when the device offers it, and the result is logged either way, because "the
 *   caller hears themselves" is otherwise unattributable.
 * - Exact frame sizes. `AudioTrack` in stream mode wants steady 20 ms writes, and a
 *   short write is a click.
 */
internal class CallAudio(
    private val context: Context,
    private val log: (String) -> Unit,
) : CallAudioPort {

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var manager: AudioManager? = null
    private var focus: Any? = null

    override var echoCancelled: Boolean = false
        private set

    /**
     * The last route this device was actually put on.
     *
     * It used to start true, left over from the probe, which is read off a screen rather than
     * held to an ear. That `true` was copied into the screen's flag when a call opened, so the
     * button believed the speaker was on, offered "Earpiece", and every tap asked for the
     * earpiece the call was already on.
     */
    override var speaker: Boolean = false
        private set

    var started: Boolean = false
        private set

    override fun start(): Boolean {
        if (started) return true

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: run {
                log("no AudioManager on this device")
                return false
            }
        manager = audioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // Focus, before the microphone opens. An outbound call gets this from Telecom through
        // its connection; an inbound one is answered by this app directly, and without asking
        // the just-finished ringtone keeps focus, the recorder returns no frames, and the call
        // looks connected while sending nothing.
        focus = requestFocus(audioManager)

        val frameBytes = G711.FRAME_SAMPLES * 2
        val input = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(G711.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val output = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(G711.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val recordSize = maxOf(
            AudioRecord.getMinBufferSize(
                G711.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            frameBytes * 4,
        )
        val trackSize = maxOf(
            AudioTrack.getMinBufferSize(
                G711.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            frameBytes * 6,
        )

        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(input)
                .setBufferSizeInBytes(recordSize)
                .build()
        } catch (fault: Exception) {
            log("AudioRecord could not be built: ${fault.message}")
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            log("AudioRecord did not initialize")
            return false
        }

        val player = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(output)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackSize)
                .build()
        } catch (fault: Exception) {
            log("AudioTrack could not be built: ${fault.message}")
            recorder.release()
            return false
        }
        if (player.state != AudioTrack.STATE_INITIALIZED) {
            player.release()
            recorder.release()
            log("AudioTrack did not initialize")
            return false
        }

        echo = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(recorder.audioSessionId)?.also {
                it.enabled = true
                echoCancelled = true
                log("echo cancellation: on")
            }
        } else {
            log("echo cancellation: not available on this device")
            null
        }

        recorder.startRecording()
        player.play()
        record = recorder
        track = player
        started = true
        log("audio: ${G711.SAMPLE_RATE} Hz mono, 16-bit, ${G711.FRAME_MILLIS} ms frames")
        setSpeaker(speaker)
        return true
    }

    /** Blocking read of at most one frame. Returns samples read, or a negative value. */
    private fun requestFocus(audioManager: AudioManager): Any? = runCatching {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .build()
            audioManager.requestAudioFocus(request)
            request
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
            attributes
        }
    }.getOrNull()

    private fun abandonFocus(audioManager: AudioManager) {
        val held = focus ?: return
        runCatching {
            if (held is android.media.AudioFocusRequest) audioManager.abandonAudioFocusRequest(held)
        }
    }

    override fun readFrame(buffer: ShortArray): Int {
        val recorder = record ?: return -1
        return try {
            recorder.read(buffer, 0, minOf(buffer.size, G711.FRAME_SAMPLES))
        } catch (fault: Exception) {
            log("microphone read failed: ${fault.message}")
            -1
        }
    }

    /** Blocking write. The track's buffer is what absorbs a late frame. */
    override fun write(samples: ShortArray, count: Int) {
        val player = track ?: return
        try {
            player.write(samples, 0, count)
        } catch (fault: Exception) {
            log("playback write failed: ${fault.message}")
        }
    }

    fun setSpeaker(on: Boolean) {
        if (TelecomBridge.telecomOwnsAudio) {
            // Telecom owns the route while it carries the call. Asking for a device here adds a
            // second route client beside the privileged one, and the unprivileged one loses,
            // so the request is a lie dressed as an action.
            log("routing is Android's while Android carries the call")
            return
        }
        speaker = on
        val audioManager = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val type = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == type }
            val routed = device != null && audioManager.setCommunicationDevice(device)
            // The streams follow the communication device only if nobody pinned them to another
            // one. Asking each stream directly is what actually moves the audio; the manager
            // call alone reports success while the track keeps playing where it started.
            if (device != null) {
                runCatching { track?.setPreferredDevice(device) }
                runCatching { record?.setPreferredDevice(device) }
            }
            val current = audioManager.communicationDevice?.type
            log(
                "routing to ${if (on) "speaker" else "earpiece"}: " +
                    "${if (routed) "set" else "refused"}, device now " +
                    when (current) {
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
                        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                            "wired headset"
                        null -> "unknown"
                        else -> current.toString()
                    },
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
            log("routing to ${if (on) "speaker" else "earpiece"}: setSpeakerphoneOn($on)")
        }
    }

    override fun useSpeaker(on: Boolean) = setSpeaker(on)

    /**
     * Drops the per-stream device pins.
     *
     * `setPreferredDevice` outranks the communication device, so a pin left over from an
     * earlier route keeps the audio there no matter what the platform decides. Telecom said
     * the route was the speaker while the call stayed in the earpiece for exactly this reason.
     */
    override fun releaseRouting() {
        runCatching { track?.setPreferredDevice(null) }
        runCatching { record?.setPreferredDevice(null) }
    }

    /**
     * Carries the platform's route down to the streams when it did not arrive on its own.
     *
     * Telecom reported the speaker while the call stayed in the earpiece. `setPreferredDevice`
     * outranks the communication device, so pinning the two streams is the one lever left that
     * moves audio the platform has already declared moved. Where the platform did apply the
     * route, the pins are dropped so they cannot disagree with it later.
     */
    override fun followRoute(onSpeaker: Boolean) {
        val audioManager = manager ?: return
        val wanted = if (onSpeaker) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == wanted }
            ?: return
        if (audioManager.communicationDevice?.type == wanted) {
            releaseRouting()
            return
        }
        runCatching { track?.setPreferredDevice(device) }
        runCatching { record?.setPreferredDevice(device) }
        log(
            "the route did not reach the streams, so they are pinned to " +
                (if (onSpeaker) "the speaker" else "the earpiece"),
        )
    }

    override fun stop() {
        runCatching { record?.stop() }
        record?.release()
        record = null
        runCatching { track?.stop() }
        track?.release()
        track = null
        echo?.release()
        echo = null
        echoCancelled = false
        started = false
        manager?.let { abandonFocus(it) }
        focus = null

        val audioManager = manager
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { audioManager.clearCommunicationDevice() }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        manager = null
        speaker = false
        log("audio stopped")
    }
}
