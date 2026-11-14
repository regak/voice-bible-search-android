package sw.bible.voicesearch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Streams PCM16 24kHz mono audio deltas from Gemini Live out through the
 * device speaker as they arrive. [stop]/[clearBuffered] handle
 * server-driven barge-in: Gemini sends `serverContent.interrupted==true`
 * when the user starts talking again, and the caller must flush
 * already-buffered-but-not-yet-played audio immediately — no separate
 * client cancel message is needed (unlike OpenAI's Realtime API).
 */
class AudioPlayback {

    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, MIN_TRACK_BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    /** Appends raw PCM16 little-endian bytes decoded from a base64 audio delta. */
    fun enqueue(pcm16Bytes: ByteArray) {
        track?.write(pcm16Bytes, 0, pcm16Bytes.size)
    }

    /** Discards buffered-but-not-yet-played audio (server-driven barge-in). */
    fun clearBuffered() {
        track?.pause()
        track?.flush()
        track?.play()
    }

    fun stop() {
        track?.apply {
            stop()
            release()
        }
        track = null
    }

    companion object {
        /** Gemini Live's PCM16 output sample rate. */
        const val SAMPLE_RATE_HZ = 24000
        private const val MIN_TRACK_BUFFER_BYTES = 24000 * 2 // ~1s of headroom
    }
}
