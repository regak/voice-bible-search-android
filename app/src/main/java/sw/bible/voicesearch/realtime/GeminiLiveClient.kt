package sw.bible.voicesearch.realtime

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Client for Google's Gemini Live API over raw WebSocket, carrying ALL
 * voice Bible-search queries (including exact references like "Yohana
 * 3:16") through Gemini's own tool-calling — no on-device grammar/parsing
 * shortcut. Ported from the sibling VoiceModeApp project's
 * GeminiLiveClient.kt with one addition: `search_bible_verse` function
 * declaration + toolCall/toolResponse dispatch to [onToolCall].
 *
 * Protocol notes (see VoiceModeApp's lessons, reproduced here so this
 * isn't re-derived blind):
 * - Endpoint: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`
 *   auth via `x-goog-api-key` as an HTTP HEADER on the handshake (NOT a
 *   `?key=` query param — that form can silently succeed the handshake
 *   while the server never processes anything after).
 * - First message after connecting MUST be `{"setup": {...}}`, and the
 *   client must wait for the server's `setupComplete` ack before sending
 *   any audio — sending even slightly early reproduces a silent stall.
 * - Audio in: 16kHz mono PCM16, base64 in `realtimeInput.audio`.
 * - Audio out: 24kHz mono PCM16 in `serverContent.modelTurn.parts[].inlineData.data`.
 * - `serverContent.interrupted == true` is server-driven barge-in — flush
 *   playback immediately, no client cancel message needed (unlike OpenAI).
 * - Tool calls arrive as a top-level `toolCall.functionCalls[]` message;
 *   reply with a top-level `toolResponse.functionResponses[]` message
 *   carrying the same `id`.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val onAudioDelta: (ByteArray) -> Unit,
    private val onTranscriptDelta: (role: String, text: String) -> Unit,
    private val onSpeechStarted: () -> Unit,
    private val onSpeechStopped: () -> Unit,
    private val onResponseDone: () -> Unit,
    private val onConnectionStateChanged: (connected: Boolean, error: String?) -> Unit,
    /** Dispatches a tool call by name/args, returning the JSON result to send back. */
    private val onToolCall: (name: String, args: JSONObject) -> JSONObject,
    private val onDebugEvent: (String) -> Unit = {},
) : VoiceBackend {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var wasInterrupted = false

    /** True only after `setupComplete` is RECEIVED (see VoiceModeApp's lesson on this). */
    @Volatile private var setupAcked = false
    private val pendingAudioChunks = java.util.concurrent.ConcurrentLinkedQueue<ShortArray>()

    override fun connect() {
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-goog-api-key", apiKey)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket open (http=${response.code})")
                onDebugEvent("WebSocket open (http=${response.code})")
                val sent = sendSetup(webSocket)
                onDebugEvent("Setup message sent=$sent (waiting for setupComplete ack)")
                // Deliberately NOT calling onConnectionStateChanged(true, ...) yet --
                // the UI should stay in a "connecting" state (not "Listening...")
                // until setupComplete actually arrives (see setupStallWatchdog).
                armSetupStallWatchdog()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "<<< $text")
                onDebugEvent(text.take(500))
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Gemini Live sends its JSON replies as BINARY frames, not
                // text frames (confirmed both via a standalone Python test
                // where ws.recv() returned `bytes`, and by this exact bug:
                // without this override, every server reply -- including
                // setupComplete -- was silently dropped by OkHttp before
                // ever reaching handleServerMessage, which is precisely the
                // "opens fine, never responds" symptom the field reports
                // showed. Same lesson already captured in the sibling
                // voice-conversation-app-android's GeminiLiveClient.
                val text = bytes.utf8()
                Log.d(TAG, "<<< (binary) $text")
                onDebugEvent(text.take(500))
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val bodySnippet = try { response?.body?.string()?.take(500) } catch (e: Exception) { null }
                Log.e(TAG, "WebSocket failure (http=${response?.code}) body=$bodySnippet", t)
                onDebugEvent("FAILURE http=${response?.code} ${t.message} body=$bodySnippet")
                cancelSetupStallWatchdog()
                onConnectionStateChanged(false, t.message ?: "Connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                cancelSetupStallWatchdog()
                onConnectionStateChanged(false, null)
            }
        })
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var setupStallRunnable: Runnable? = null

    /**
     * The handshake (http=101) succeeding only proves the WebSocket upgrade
     * worked -- it does NOT prove Gemini Live is actually processing
     * anything afterward. On a device with no logcat/ADB access, a network
     * that interferes with WebSocket data frames after the upgrade (seen on
     * some mobile carriers / restrictive networks) looks identical in the
     * UI to a working session that just never gets a reply: the orb sat on
     * "Listening..." forever with zero further debug-log lines in the field
     * report that caught this. Surface that as a real, dismissible error
     * instead of hanging silently, so the user knows to check their network
     * rather than assuming the app is broken.
     */
    private fun armSetupStallWatchdog() {
        cancelSetupStallWatchdog()
        val runnable = Runnable {
            if (!setupAcked) {
                onDebugEvent("No setupComplete after ${SETUP_TIMEOUT_MS}ms -- likely network issue")
                onConnectionStateChanged(
                    false,
                    "No response from Gemini Live after ${SETUP_TIMEOUT_MS / 1000}s. " +
                        "Check your internet connection and try again.",
                )
                webSocket?.close(1000, "setup timeout")
                webSocket = null
            }
        }
        setupStallRunnable = runnable
        mainHandler.postDelayed(runnable, SETUP_TIMEOUT_MS)
    }

    private fun cancelSetupStallWatchdog() {
        setupStallRunnable?.let { mainHandler.removeCallbacks(it) }
        setupStallRunnable = null
    }

    private fun sendSetup(webSocket: WebSocket): Boolean {
        val setup = JSONObject().apply {
            put(
                "setup",
                JSONObject().apply {
                    put("model", "models/$MODEL")
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("responseModalities", JSONArray().put("AUDIO"))
                        },
                    )
                    put(
                        "systemInstruction",
                        JSONObject().apply {
                            put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "text",
                                        "Wewe ni msaidizi wa sauti wa kutafuta mistari ya Biblia kwa " +
                                            "lugha ya Kiswahili. Mtumiaji atakuuliza kwa sauti kuhusu " +
                                            "mistari ya Biblia (kwa mfano 'Yohana 3:16' au mada kama " +
                                            "'upendo'). KILA WAKATI mtumiaji anapoomba andiko la Biblia " +
                                            "(iwe rejea kamili au mada), ITA chombo search_bible_verse " +
                                            "ili kupata maandiko sahihi — kamwe usijaribu kukariri au " +
                                            "kubuni maandiko wewe mwenyewe. Baada ya kupata matokeo, " +
                                            "soma andiko kwa sauti kwa Kiswahili fasaha.",
                                    ),
                                ),
                            )
                        },
                    )
                    put("tools", JSONArray().put(buildToolDeclaration()))
                    // Transcripts opt-in for debug UI / logging.
                    put("inputAudioTranscription", JSONObject())
                    put("outputAudioTranscription", JSONObject())
                },
            )
        }
        return webSocket.send(setup.toString())
    }

    private fun buildToolDeclaration(): JSONObject = JSONObject().apply {
        put(
            "functionDeclarations",
            JSONArray().put(
                JSONObject().apply {
                    put("name", "search_bible_verse")
                    put(
                        "description",
                        "Tafuta mstari/mistari ya Biblia katika hifadhidata ya ndani ya simu " +
                            "(nje ya mtandao). Tumia 'book'+'chapter'(+'verse') kwa rejea kamili " +
                            "(mfano Yohana 3:16), au 'query' kwa utafutaji wa mada (mfano 'upendo').",
                    )
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "OBJECT")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Neno/mada ya kutafuta kwa maandishi (hiari).")
                                    })
                                    put("book", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Jina la kitabu cha Biblia kwa Kiswahili, mfano 'Yohana' (hiari).")
                                    })
                                    put("chapter", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Namba ya sura (hiari).")
                                    })
                                    put("verse", JSONObject().apply {
                                        put("type", "INTEGER")
                                        put("description", "Namba ya mstari (hiari).")
                                    })
                                },
                            )
                        },
                    )
                },
            ),
        )
    }

    /** Sends one chunk of raw PCM16 mic audio, expected at [INPUT_SAMPLE_RATE_HZ]. */
    override fun sendAudioChunk(pcm16: ShortArray) {
        if (!setupAcked) {
            pendingAudioChunks.add(pcm16)
            return
        }
        sendAudioChunkNow(pcm16)
    }

    private fun sendAudioChunkNow(pcm16: ShortArray) {
        val bytes = shortsToLittleEndianBytes(pcm16)
        val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val event = JSONObject().apply {
            put(
                "realtimeInput",
                JSONObject().apply {
                    put(
                        "audio",
                        JSONObject().apply {
                            put("data", base64Audio)
                            put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE_HZ")
                        },
                    )
                },
            )
        }
        webSocket?.send(event.toString())
    }

    /**
     * No-op: Gemini Live's default ActivityHandling already auto-interrupts
     * generation on detected user speech — see VoiceModeApp's class doc.
     */
    override fun cancelResponse() {
        // Intentionally empty.
    }

    override fun disconnect() {
        cancelSetupStallWatchdog()
        webSocket?.close(1000, "User ended session")
        webSocket = null
        setupAcked = false
        pendingAudioChunks.clear()
    }

    private fun handleServerMessage(rawJson: String) {
        val message = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed server message", e)
            return
        }

        if (message.has("setupComplete")) {
            Log.i(TAG, "Gemini Live setup complete")
            cancelSetupStallWatchdog()
            setupAcked = true
            onConnectionStateChanged(true, null)
            var flushed = 0
            while (true) {
                val chunk = pendingAudioChunks.poll() ?: break
                sendAudioChunkNow(chunk)
                flushed++
            }
            onDebugEvent("setupComplete received — flushed $flushed buffered audio chunk(s)")
            return
        }

        val toolCall = message.optJSONObject("toolCall")
        if (toolCall != null) {
            handleToolCall(toolCall)
            return
        }

        val serverContent = message.optJSONObject("serverContent")
        if (serverContent != null) {
            if (serverContent.optBoolean("interrupted", false)) {
                wasInterrupted = true
                onSpeechStarted()
            }

            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inlineData = part.optJSONObject("inlineData")
                    val base64Audio = inlineData?.optString("data")
                    if (!base64Audio.isNullOrEmpty()) {
                        onAudioDelta(Base64.decode(base64Audio, Base64.DEFAULT))
                    }
                }
            }

            val outputTranscription = serverContent.optJSONObject("outputTranscription")
            val outputText = outputTranscription?.optString("text")
            if (!outputText.isNullOrEmpty()) {
                onTranscriptDelta("assistant", outputText)
            }

            val inputTranscription = serverContent.optJSONObject("inputTranscription")
            val inputText = inputTranscription?.optString("text")
            if (!inputText.isNullOrEmpty()) {
                onTranscriptDelta("user", inputText)
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                if (!wasInterrupted) {
                    onSpeechStopped()
                }
                wasInterrupted = false
                onResponseDone()
            }
        }

        val voiceActivity = message.optJSONObject("voiceActivity")
        if (voiceActivity != null) {
            when (voiceActivity.optString("type")) {
                "ACTIVITY_START" -> onSpeechStarted()
                "ACTIVITY_END" -> onSpeechStopped()
            }
        }
    }

    /** Dispatches each requested function call to [onToolCall] and replies with toolResponse. */
    private fun handleToolCall(toolCall: JSONObject) {
        val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
        val responses = JSONArray()
        for (i in 0 until functionCalls.length()) {
            val call = functionCalls.optJSONObject(i) ?: continue
            val name = call.optString("name")
            val id = call.optString("id")
            val args = call.optJSONObject("args") ?: JSONObject()
            onDebugEvent("toolCall: $name($args)")
            val resultJson = try {
                onToolCall(name, args)
            } catch (e: Exception) {
                onDebugEvent("toolCall error: ${e.message}")
                JSONObject().put("error", e.message ?: "unknown error")
            }
            responses.put(
                JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("response", resultJson)
                },
            )
        }
        val toolResponse = JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", responses),
        )
        webSocket?.send(toolResponse.toString())
        onDebugEvent("toolResponse sent for ${functionCalls.length()} call(s)")
    }

    private fun shortsToLittleEndianBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val value = shorts[i].toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    companion object {
        private const val TAG = "VoiceBible/GeminiLive"
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        // Matches the model already confirmed working on-device in the
        // sibling voice-conversation-app-android -- "-preview" models can
        // be geo/account-restricted in ways that succeed from a dev
        // machine's IP but silently fail (no setupComplete, no error frame)
        // from a different network/region, which is what this app's field
        // report looked like. Use the same known-good model instead of a
        // preview one neither sibling app relies on.
        private const val MODEL = "gemini-3.8-live"

        /** How long to wait for `setupComplete` before surfacing a timeout error. */
        private const val SETUP_TIMEOUT_MS = 15_000L

        /** Gemini Live's expected mic input sample rate. */
        const val INPUT_SAMPLE_RATE_HZ = 16000

        /** Gemini Live's PCM16 output sample rate. */
        const val OUTPUT_SAMPLE_RATE_HZ = 24000
    }
}
