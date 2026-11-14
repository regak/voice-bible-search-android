package sw.bible.voicesearch.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import sw.bible.voicesearch.BuildConfig
import sw.bible.voicesearch.audio.AudioCapture
import sw.bible.voicesearch.audio.AudioPlayback
import sw.bible.voicesearch.bible.BibleRepository
import sw.bible.voicesearch.realtime.GeminiLiveClient
import sw.bible.voicesearch.realtime.VoiceBackend

/**
 * Single-screen voice Bible-search assistant: a big orb-style status
 * indicator (idle/listening/thinking/speaking/error, same UX shape as
 * the sibling voice-conversation-app-android), tap to start/stop a
 * continuous Gemini Live session. ALL queries — including exact
 * references like "Yohana 3:16" — are routed through Gemini Live's
 * tool-calling into [BibleRepository]'s local offline SQLite lookup;
 * there is no on-device grammar/parsing shortcut and no offline STT
 * fallback (voice always requires network).
 */
private enum class SessionState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Screen { VOICE, CREDITS }

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.VOICE) }
    when (screen) {
        Screen.VOICE -> VoiceBibleSearchScreen(onShowCredits = { screen = Screen.CREDITS })
        Screen.CREDITS -> CreditsScreen(onBack = { screen = Screen.VOICE })
    }
}

@Composable
private fun VoiceBibleSearchScreen(onShowCredits: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sessionState by remember { mutableStateOf(SessionState.IDLE) }
    var statusText by remember { mutableStateOf("Bonyeza ili kuongea") } // "Tap to start talking"
    var lastErrorText by remember { mutableStateOf<String?>(null) }

    var activeSession by remember { mutableStateOf<ActiveSession?>(null) }
    val audioPlayback = remember { AudioPlayback() }
    val bibleRepository = remember { BibleRepository(context) }

    var debugLog by remember { mutableStateOf(listOf<String>()) }
    var chunkCounterLine by remember { mutableStateOf<String?>(null) }
    fun appendDebug(line: String) {
        if (line.startsWith("AudioCapture: sent chunk")) {
            chunkCounterLine = line
        } else {
            debugLog = (debugLog + line).takeLast(20)
        }
    }

    var micPermissionGranted by remember {
        mutableStateOf(AudioCapture(context).hasMicPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micPermissionGranted = granted
        if (!granted) {
            statusText = "Ruhusa ya kipaza sauti inahitajika." // mic permission required
        }
    }

    fun onAudioDelta(bytes: ByteArray) {
        audioPlayback.enqueue(bytes)
        sessionState = SessionState.SPEAKING
        statusText = "Inasema..." // Speaking...
    }
    fun onSpeechStarted() {
        if (sessionState == SessionState.SPEAKING) {
            audioPlayback.clearBuffered()
        }
        sessionState = SessionState.LISTENING
        statusText = "Inasikiliza..." // Listening...
    }
    fun onSpeechStopped() {
        sessionState = SessionState.THINKING
        statusText = "Inafikiri..." // Thinking...
    }
    fun onResponseDone() {
        if (sessionState != SessionState.LISTENING) {
            sessionState = SessionState.LISTENING
            statusText = "Inasikiliza..."
        }
    }
    fun onConnectionStateChanged(connected: Boolean, error: String?) {
        if (error != null) {
            lastErrorText = error
            sessionState = SessionState.ERROR
            statusText = "Hitilafu: $error"
        } else if (connected) {
            sessionState = SessionState.LISTENING
            statusText = "Inasikiliza..."
        } else {
            sessionState = SessionState.IDLE
            statusText = "Bonyeza ili kuongea"
        }
    }

    /**
     * Native handler for the `search_bible_verse` tool, backed entirely
     * by [BibleRepository]'s local SQLite database — never a network
     * call. Returns the JSON object sent back to Gemini Live as the
     * functionResponse payload.
     */
    fun onToolCall(name: String, args: JSONObject): JSONObject {
        if (name != "search_bible_verse") {
            return JSONObject().put("error", "unknown tool: $name")
        }
        val query = args.optString("query").ifBlank { null }
        val book = args.optString("book").ifBlank { null }
        val chapter = if (args.has("chapter")) args.optInt("chapter") else null
        val verse = if (args.has("verse")) args.optInt("verse") else null
        val results = bibleRepository.searchBibleVerse(query, book, chapter, verse)
        val resultsJson = JSONArray()
        results.forEach { r ->
            resultsJson.put(
                JSONObject().apply {
                    put("reference", r.reference)
                    put("book", r.book)
                    put("chapter", r.chapter)
                    put("verse", r.verse)
                    put("text", r.text)
                },
            )
        }
        appendDebug("search_bible_verse(query=$query, book=$book, chapter=$chapter, verse=$verse) -> ${results.size} result(s)")
        return JSONObject().apply {
            put("results", resultsJson)
            put("count", results.size)
        }
    }

    val startSession: () -> Unit = {
        if (!micPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            statusText = "Hakuna ufunguo wa Gemini API. Weka GEMINI_API_KEY katika local.properties."
            sessionState = SessionState.ERROR
        } else {
            sessionState = SessionState.CONNECTING
            statusText = "Inaunganisha..." // Connecting...
            lastErrorText = null
            debugLog = emptyList()
            chunkCounterLine = null
            audioPlayback.start()

            val voiceBackend: VoiceBackend = GeminiLiveClient(
                apiKey = BuildConfig.GEMINI_API_KEY,
                onAudioDelta = ::onAudioDelta,
                onTranscriptDelta = { _, _ -> },
                onSpeechStarted = ::onSpeechStarted,
                onSpeechStopped = ::onSpeechStopped,
                onResponseDone = ::onResponseDone,
                onConnectionStateChanged = ::onConnectionStateChanged,
                onToolCall = ::onToolCall,
                onDebugEvent = ::appendDebug,
            )
            val audioCapture = AudioCapture(context, sampleRateHz = GeminiLiveClient.INPUT_SAMPLE_RATE_HZ, onDebugEvent = ::appendDebug)

            voiceBackend.connect()
            coroutineScope.launch {
                val chunks = audioCapture.start(this)
                chunks.consumeEach { chunk -> voiceBackend.sendAudioChunk(chunk) }
            }

            activeSession = ActiveSession(voiceBackend, audioCapture)
        }
    }

    val stopSession: () -> Unit = {
        activeSession?.audioCapture?.stop()
        audioPlayback.stop()
        activeSession?.backend?.disconnect()
        activeSession = null
        sessionState = SessionState.IDLE
        statusText = "Bonyeza ili kuongea"
    }

    DisposableEffect(Unit) {
        onDispose {
            activeSession?.audioCapture?.stop()
            audioPlayback.stop()
            activeSession?.backend?.disconnect()
            activeSession = null
        }
    }

    val orbColor = when (sessionState) {
        SessionState.IDLE -> Color(0xFF9E9E9E)
        SessionState.CONNECTING -> Color(0xFFFFC107)
        SessionState.LISTENING -> Color(0xFF2196F3)
        SessionState.THINKING -> Color(0xFF9C27B0)
        SessionState.SPEAKING -> Color(0xFF4CAF50)
        SessionState.ERROR -> Color(0xFFF44336)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(sessionState) {
                detectTapGestures(
                    onTap = {
                        when (sessionState) {
                            SessionState.IDLE, SessionState.ERROR -> startSession()
                            else -> stopSession()
                        }
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Tafuta Mstari wa Biblia kwa Sauti", // Voice Bible Search
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 24.dp),
        )
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(color = orbColor, shape = CircleShape),
        )
        Text(
            text = statusText,
            modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
            fontSize = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (sessionState != SessionState.IDLE) {
            Text(
                text = "Bonyeza tena ili kusitisha", // Tap again to end
                modifier = Modifier.padding(top = 12.dp),
                fontSize = 13.sp,
                color = Color.Gray,
            )
        }

        Text(
            text = "Credits / Biblica attribution",
            modifier = Modifier
                .padding(top = 32.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { onShowCredits() }) },
            fontSize = 12.sp,
            color = Color(0xFF2196F3),
        )

        if (debugLog.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .padding(top = 24.dp, start = 16.dp, end = 16.dp)
                    .background(color = Color(0xFF212121), shape = RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Debug log (${debugLog.size} events):",
                    fontSize = 11.sp,
                    color = Color(0xFFBBBBBB),
                )
                chunkCounterLine?.let { line ->
                    Text(
                        text = line,
                        fontSize = 10.sp,
                        color = Color(0xFF64B5F6),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                debugLog.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 10.sp,
                        color = Color(0xFF80E27E),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Required CC BY-SA 4.0 attribution + Biblica(R) trademark notice for
 * the bundled "Toleo Wazi Neno: Maandiko Matakatifu" text, per the
 * license at https://creativecommons.org/licenses/by-sa/4.0/.
 */
@Composable
private fun CreditsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Rudi", // Back
            fontSize = 16.sp,
            color = Color(0xFF2196F3),
            modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onBack() }) },
        )
        Text(
            text = "Credits",
            fontSize = 22.sp,
            modifier = Modifier.padding(top = 24.dp, bottom = 16.dp),
        )
        Text(
            text = "Maandiko ya Biblia yaliyotumika katika programu hii ni kutoka:\n\n" +
                "Biblica\u00AE Toleo Wazi Neno: Maandiko Matakatifu\u2122 (ONMM\u2122)\n" +
                "Hakimiliki \u00A9 2018, 2024 na Biblica, Inc.\n\n" +
                "Biblica\u00AE Open Kiswahili Contemporary Scriptures\u2122\n" +
                "Copyright \u00A9 2018, 2024 by Biblica, Inc.\n\n" +
                "Kazi hii imewezeshwa kupitia idhini ya Creative Commons " +
                "Attribution-ShareAlike 4.0 International License (CC BY-SA 4.0). " +
                "Ili kuona nakala ya idhini hii, tembelea " +
                "http://creativecommons.org/licenses/by-sa/4.0/\n\n" +
                "Biblica\u00AE ni alama ya biashara iliyosajiliwa ya Biblica, Inc. " +
                "Matumizi ya alama hii yanahitaji ruhusa ya maandishi kutoka Biblica, Inc.\n\n" +
                "Kazi ya asili inapatikana bila malipo katika www.biblica.com na open.bible.\n\n" +
                "Source: https://ebible.org/swhonmm/ (eBible.org / Crosswire SWORD module).",
            fontSize = 14.sp,
        )
    }
}

/** Bundles the live backend + capture instances so [stopSession] can tear both down. */
private data class ActiveSession(val backend: VoiceBackend, val audioCapture: AudioCapture)
