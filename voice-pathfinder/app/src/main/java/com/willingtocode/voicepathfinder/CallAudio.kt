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

    override var echoCancelled: Boolean = false
        private set

    /** Starts on the speaker: this probe is read off a screen, not held to an ear. */
    override var speaker: Boolean = true
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
        speaker = on
        val audioManager = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val type = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == type }
            val routed = device != null && audioManager.setCommunicationDevice(device)
            log("routing to ${if (on) "speaker" else "earpiece"}: ${if (routed) "set" else "refused"}")
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
            log("routing to ${if (on) "speaker" else "earpiece"}: setSpeakerphoneOn($on)")
        }
    }

    override fun useSpeaker(on: Boolean) = setSpeaker(on)

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
