package sw.bible.voicesearch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streams PCM16 24kHz mono audio deltas from Gemini Live out through the
 * device speaker as they arrive. [stop]/[clearBuffered] handle
 * server-driven barge-in: Gemini sends `serverContent.interrupted==true`
 * when the user starts talking again, and the caller must flush
 * already-buffered-but-not-yet-played audio immediately — no separate
 * client cancel message is needed (unlike OpenAI's Realtime API).
 *
 * [enqueue] is called directly from the WebSocket listener's onMessage
 * callback -- it must NEVER block. AudioTrack.write() in MODE_STREAM
 * blocks until buffer space frees up, so writing synchronously on that
 * thread can stall processing of the NEXT incoming server message
 * (including the `interrupted` flag that is supposed to cut playback
 * off), which is exactly what made interrupting feel broken/unresponsive
 * compared to the sibling voice-conversation-app-android -- its
 * AudioEngine already decouples playback the same way this does now:
 * [enqueue] only queues bytes (non-blocking), and a dedicated consumer
 * coroutine does the actual blocking AudioTrack.write().
 */
class AudioPlayback {

    private var track: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var queue: Channel<ByteArray>? = null
    private var consumerJob: Job? = null

    fun start() {
        if (track != null) return
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_MEDIA (not USAGE_VOICE_COMMUNICATION) -- matches
                    // the sibling voice-conversation-app-android's working
                    // AudioEngine. USAGE_VOICE_COMMUNICATION routes through
                    // Android's call audio stream (lower gain, echo-cancel
                    // processing, sometimes earpiece-biased) instead of the
                    // regular media stream, which is why this app's output
                    // was noticeably quieter than the sibling app's on the
                    // same device/volume setting.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
        newTrack.play()
        track = newTrack

        val newQueue = Channel<ByteArray>(capacity = 256)
        queue = newQueue
        consumerJob = scope.launch {
            while (isActive) {
                val chunk = newQueue.receiveCatching().getOrNull() ?: break
                runCatching { newTrack.write(chunk, 0, chunk.size) }
            }
        }
    }

    /** Queues raw PCM16 little-endian bytes decoded from a base64 audio delta. Never blocks. */
    fun enqueue(pcm16Bytes: ByteArray) {
        queue?.trySend(pcm16Bytes)
    }

    /** Discards buffered-but-not-yet-played audio (server-driven barge-in). */
    fun clearBuffered() {
        // Drain anything still sitting in the queue first -- otherwise the
        // consumer coroutine would keep writing stale audio to the track
        // right after we flush it, undoing the flush within milliseconds.
        while (queue?.tryReceive()?.isSuccess == true) {
            // discarded
        }
        track?.pause()
        track?.flush()
        track?.play()
    }

    fun stop() {
        consumerJob?.cancel()
        consumerJob = null
        queue?.close()
        queue = null
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
