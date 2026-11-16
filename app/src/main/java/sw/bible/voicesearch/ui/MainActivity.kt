package sw.bible.voicesearch.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
 * Single-screen voice Bible-search assistant. UI deliberately mirrors
 * the sibling voice-conversation-app-android's "VoiceOrbScreen" look —
 * same ChatGPT-voice-mode-style layout: round top-bar icon buttons, a
 * breathing/pulsing gradient orb, a scrollable transcript, a dismissible
 * error banner, and a bottom pill bar with mic-mute + start/end button —
 * adapted to this app's own states (adds THINKING, since a tool-calling
 * verse lookup has a visible "thinking" beat the plain conversation app
 * doesn't need) and swaps the top bar's nav-drawer icon for a book icon
 * that opens this app's Credits/attribution screen.
 *
 * ALL queries — including exact references like "Yohana 3:16" — are
 * routed through Gemini Live's tool-calling into [BibleRepository]'s
 * local offline SQLite lookup; there is no on-device grammar/parsing
 * shortcut and no offline STT fallback (voice always requires network).
 */
private enum class SessionState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

private data class TranscriptLine(val isModel: Boolean, val text: String)

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
    var lastErrorText by remember { mutableStateOf<String?>(null) }
    var showInfoBanner by remember { mutableStateOf(true) }
    var showDebug by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }

    var activeSession by remember { mutableStateOf<ActiveSession?>(null) }
    val audioPlayback = remember { AudioPlayback() }
    val bibleRepository = remember { BibleRepository(context) }

    var transcript by remember { mutableStateOf(listOf<TranscriptLine>()) }
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
            lastErrorText = "Microphone permission is required."
            sessionState = SessionState.ERROR
            showInfoBanner = true
        }
    }

    fun onAudioDelta(bytes: ByteArray) {
        audioPlayback.enqueue(bytes)
        sessionState = SessionState.SPEAKING
    }
    fun onSpeechStarted() {
        if (sessionState == SessionState.SPEAKING) {
            audioPlayback.clearBuffered()
        }
        sessionState = SessionState.LISTENING
    }
    fun onSpeechStopped() {
        sessionState = SessionState.THINKING
    }
    fun onResponseDone() {
        if (sessionState != SessionState.LISTENING) {
            sessionState = SessionState.LISTENING
        }
    }
    fun onTranscriptDelta(role: String, text: String) {
        if (text.isBlank()) return
        transcript = transcript + TranscriptLine(isModel = role == "assistant", text = text)
    }
    fun onConnectionStateChanged(connected: Boolean, error: String?) {
        if (error != null) {
            lastErrorText = error
            sessionState = SessionState.ERROR
            showInfoBanner = true
        } else if (connected) {
            sessionState = SessionState.LISTENING
        } else {
            sessionState = SessionState.IDLE
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
            lastErrorText = "No Gemini API key. Set GEMINI_API_KEY in local.properties."
            sessionState = SessionState.ERROR
            showInfoBanner = true
        } else {
            sessionState = SessionState.CONNECTING
            lastErrorText = null
            debugLog = emptyList()
            chunkCounterLine = null
            audioPlayback.start()

            val voiceBackend: VoiceBackend = GeminiLiveClient(
                apiKey = BuildConfig.GEMINI_API_KEY,
                onAudioDelta = ::onAudioDelta,
                onTranscriptDelta = ::onTranscriptDelta,
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
    }

    DisposableEffect(Unit) {
        onDispose {
            activeSession?.audioCapture?.stop()
            audioPlayback.stop()
            activeSession?.backend?.disconnect()
            activeSession = null
        }
    }

    val sessionActive = sessionState != SessionState.IDLE && sessionState != SessionState.ERROR

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar: two round icon buttons, same ChatGPT voice-mode shape
            // as the sibling app — book icon opens Credits/attribution
            // instead of a nav drawer, tune icon toggles the debug panel.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RoundIconButton(icon = Icons.AutoMirrored.Filled.MenuBook, onClick = onShowCredits)
                RoundIconButton(icon = Icons.Filled.Tune, onClick = { showDebug = !showDebug })
            }

            Text(
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = Color(0xFFC0C4CC),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            // Scrollable conversation history: the user's spoken question on
            // the right, the matched verse(s) read back on the left.
            val listState = rememberLazyListState()
            LaunchedEffect(transcript.size) {
                if (transcript.isNotEmpty()) {
                    listState.animateScrollToItem(transcript.size - 1)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp),
            ) {
                items(transcript) { line ->
                    if (line.isModel) {
                        Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                            Text(text = line.text, color = Color(0xFF1A1A1A), fontSize = 16.sp)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFFDCE7FB))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(text = line.text, color = Color(0xFF1A1A1A), fontSize = 16.sp)
                            }
                        }
                    }
                }
            }

            // Centerpiece orb + status text.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Orb(
                    state = sessionState,
                    modifier = Modifier
                        .size(180.dp)
                        .clickable {
                            if (!sessionActive) startSession()
                        },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = statusText(sessionState, lastErrorText),
                color = Color(0xFF8A8F98),
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))

            AnimatedVisibility(visible = lastErrorText != null && showInfoBanner, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFF8A8F98))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Something went wrong", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            text = lastErrorText.orEmpty(),
                            color = Color(0xFF6B7280),
                            fontSize = 13.sp,
                            maxLines = 6,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = Color(0xFF8A8F98),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { showInfoBanner = false },
                    )
                }
            }

            // Bottom pill bar (decorative text field -- voice-only app, no
            // text chat backend) + mute + end/start, same shape as the
            // sibling app's bottom bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Color(0xFF8A8F98))
                    Spacer(Modifier.width(12.dp))
                    Text("Ask for a Bible verse", color = Color(0xFFADB1B8), fontSize = 16.sp)
                }

                Spacer(Modifier.width(10.dp))

                RoundIconButton(
                    icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    onClick = { isMuted = !isMuted },
                    background = Color.White,
                )

                Spacer(Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .clickable {
                            if (sessionActive) stopSession() else startSession()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (sessionActive) Icons.Filled.Close else Icons.Filled.Mic,
                        contentDescription = if (sessionActive) "End conversation" else "Start conversation",
                        tint = Color.White,
                    )
                }
            }
        }

        // Debug panel overlay, toggled by the top-right tune icon -- kept
        // for troubleshooting, hidden by default.
        AnimatedVisibility(
            visible = showDebug,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(Color(0xFF1C1C1E))
                    .padding(12.dp),
            ) {
                Text("Debug log", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                chunkCounterLine?.let { line ->
                    Text(text = line, color = Color(0xFF64B5F6), fontSize = 10.sp)
                }
                LazyColumn {
                    items(debugLog) { line ->
                        Text(
                            text = line,
                            color = Color(0xFF9FE870),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun statusText(state: SessionState, error: String?): String = when (state) {
    SessionState.IDLE -> "Tap the orb to ask for a verse"
    SessionState.CONNECTING -> "Connecting..."
    SessionState.LISTENING -> "Listening..."
    SessionState.THINKING -> "Thinking..."
    SessionState.SPEAKING -> "Speaking..."
    SessionState.ERROR -> if (error != null) "Error" else "Something went wrong"
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    background: Color = Color.White,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF1A1A1A))
    }
}

/**
 * ChatGPT-voice-mode-inspired gradient orb, same shape/animation as the
 * sibling voice-conversation-app-android's Orb — breathes gently when
 * idle/listening, pulses faster when speaking, plus a distinct purple
 * "thinking" state this app needs for the verse-lookup tool call that
 * the plain conversation app doesn't have.
 */
@Composable
private fun Orb(state: SessionState, modifier: Modifier = Modifier) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = when (state) {
            SessionState.SPEAKING -> 1.12f
            SessionState.LISTENING -> 1.04f
            SessionState.CONNECTING -> 1.02f
            SessionState.THINKING -> 1.06f
            else -> 1.0f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (state == SessionState.SPEAKING) 500 else 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val gradientColors = when (state) {
        SessionState.ERROR -> listOf(Color(0xFFE53935), Color(0xFFFFCDD2), Color(0xFFFFFFFF))
        SessionState.CONNECTING -> listOf(Color(0xFF90A4AE), Color(0xFFCFD8DC), Color(0xFFFFFFFF))
        SessionState.SPEAKING -> listOf(Color(0xFF2962FF), Color(0xFF82B1FF), Color(0xFFE3F2FD))
        SessionState.LISTENING -> listOf(Color(0xFF1E88E5), Color(0xFF64B5F6), Color(0xFFE8F0FE))
        SessionState.THINKING -> listOf(Color(0xFF7C4DFF), Color(0xFFB39DDB), Color(0xFFEDE7F6))
        SessionState.IDLE -> listOf(Color(0xFF3B5FE0), Color(0xFF9DB9F5), Color(0xFFEFF3FD))
    }
    Box(
        modifier = modifier
            .scale(pulse)
            .clip(CircleShape)
            .background(
                brush = Brush.verticalGradient(gradientColors),
            ),
    )
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
            text = "Back",
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
