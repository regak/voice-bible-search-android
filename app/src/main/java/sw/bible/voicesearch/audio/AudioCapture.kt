package sw.bible.voicesearch.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures mic audio at [SAMPLE_RATE_HZ] (16kHz mono PCM16 — Gemini Live's
 * expected mic input rate) and hands off fixed-size chunks through a
 * single-consumer [Channel].
 *
 * Ported from the sibling voice-conversation-app-android project's
 * AudioCapture.kt (same discipline: buffers are always copied before
 * leaving the read loop, since [AudioRecord.read] reuses the backing
 * array on the next call). Continuous stream for the whole voice
 * session — the server's own VAD decides when the user started/stopped
 * talking, there is no manual record button.
 */
class AudioCapture(
    private val context: Context,
    private val sampleRateHz: Int = SAMPLE_RATE_HZ,
    private val onDebugEvent: (String) -> Unit = {},
) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: kotlinx.coroutines.Job? = null

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // caller must check hasMicPermission() first
    fun start(scope: CoroutineScope): ReceiveChannel<ShortArray> {
        check(hasMicPermission()) { "RECORD_AUDIO permission not granted" }

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val chunkSamples = sampleRateHz / 50 // 20ms chunks
        val bufferSize = maxOf(minBufferSize, chunkSamples * 4)
        onDebugEvent("AudioCapture: rate=$sampleRateHz minBufferSize=$minBufferSize bufferSize=$bufferSize")

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        audioRecord = record

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onDebugEvent("AudioCapture: FAILED TO INITIALIZE (state=${record.state}) at ${sampleRateHz}Hz")
        }

        val channel = Channel<ShortArray>(capacity = Channel.UNLIMITED)
        record.startRecording()
        onDebugEvent("AudioCapture: recordingState=${record.recordingState} (3=RECORDING)")

        var chunksSent = 0
        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(chunkSamples)
            while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    channel.send(buffer.copyOf(read))
                    chunksSent++
                    if (chunksSent == 1 || chunksSent % 100 == 0) {
                        onDebugEvent("AudioCapture: sent chunk #$chunksSent ($read samples)")
                    }
                } else if (read < 0) {
                    onDebugEvent("AudioCapture: read() error code=$read")
                }
            }
            channel.close()
        }

        return channel
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        audioRecord = null
    }

    companion object {
        /** Gemini Live's expected mic input sample rate. */
        const val SAMPLE_RATE_HZ = 16000
    }
}
