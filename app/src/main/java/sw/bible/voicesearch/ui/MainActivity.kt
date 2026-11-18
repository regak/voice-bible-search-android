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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import sw.bible.voicesearch.BuildConfig
import sw.bible.voicesearch.audio.AudioCapture
import sw.bible.voicesearch.audio.AudioPlayback
import sw.bible.voicesearch.bible.BibleRepository
import sw.bible.voicesearch.realtime.GeminiLiveClient
import sw.bible.voicesearch.realtime.VoiceBackend

/**
 * Single-screen voice Bible-search assistant. Results render as result
 * CARDS (query pill + status line + a verse card with a "Sikiliza"
 * replay button) rather than a chat-bubble conversation — each spoken
 * query produces one self-contained card, matching the reference
 * design the user supplied, instead of an open-ended transcript.
 *
 * ALL queries — including exact references like "Yohana 3:16" — are
 * routed through Gemini Live's tool-calling into [BibleRepository]'s
 * local offline SQLite lookup; there is no on-device grammar/parsing
 * shortcut and no offline STT fallback (voice always requires network).
 */
private enum class SessionState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR }

/** One completed voice exchange: the spoken query + the matched verse(s) + its audio for replay. */
private data class VerseCard(
    val query: String,
    val reference: String,
    val verses: List<VerseLine>,
    val audio: ByteArray?,
)

/** One verse within a card -- a card can hold a single verse, a range, or a whole chapter. */
private data class VerseLine(val verse: Int, val text: String)

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

    var activeSession by remember { mutableStateOf<ActiveSession?>(null) }
    val audioPlayback = remember { AudioPlayback() }
    val bibleRepository = remember { BibleRepository(context) }

    var cards by remember { mutableStateOf(listOf<VerseCard>()) }

    // Accumulated per-turn state: built up while LISTENING/THINKING/SPEAKING,
    // flushed into one VerseCard on turnComplete, then reset for the next query.
    var pendingQuery by remember { mutableStateOf("") }
    var pendingReference by remember { mutableStateOf<String?>(null) }
    var pendingVerses by remember { mutableStateOf<List<VerseLine>>(emptyList()) }
    val pendingAudioChunks = remember { mutableListOf<ByteArray>() }

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
        pendingAudioChunks.add(bytes)
        sessionState = SessionState.SPEAKING
    }
    fun onSpeechStarted() {
        if (sessionState == SessionState.SPEAKING) {
            audioPlayback.clearBuffered()
        }
        // A new utterance is starting -- reset the in-progress card so the
        // previous one's leftover audio/text never bleeds into this query.
        pendingQuery = ""
        pendingReference = null
        pendingVerses = emptyList()
        pendingAudioChunks.clear()
        sessionState = SessionState.LISTENING
    }
    fun onSpeechStopped() {
        sessionState = SessionState.THINKING
    }
    fun onResponseDone() {
        // Flush the completed turn into one result card (query + matched
        // verse(s) + its own replayable audio), then reset for the next query.
        val reference = pendingReference
        val verses = pendingVerses
        if (reference != null && verses.isNotEmpty()) {
            val combinedAudio = if (pendingAudioChunks.isEmpty()) {
                null
            } else {
                val out = ByteArrayOutputStream()
                pendingAudioChunks.forEach { out.write(it) }
                out.toByteArray()
            }
            cards = cards + VerseCard(
                query = pendingQuery.trim(),
                reference = reference,
                verses = verses,
                audio = combinedAudio,
            )
        }
        pendingQuery = ""
        pendingReference = null
        pendingVerses = emptyList()
        pendingAudioChunks.clear()
        if (sessionState != SessionState.LISTENING) {
            sessionState = SessionState.LISTENING
        }
    }
    fun onTranscriptDelta(role: String, text: String) {
        if (text.isBlank()) return
        if (role == "user") {
            pendingQuery += text
        }
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
     * functionResponse payload, and stashes ALL matched verses (not just
     * the first) so [onResponseDone] can turn a whole chapter or a verse
     * range into one multi-line result card, not just a single verse.
     */
    fun onToolCall(name: String, args: JSONObject): JSONObject {
        if (name != "search_bible_verse") {
            return JSONObject().put("error", "unknown tool: $name")
        }
        val query = args.optString("query").ifBlank { null }
        val book = args.optString("book").ifBlank { null }
        val chapter = if (args.has("chapter")) args.optInt("chapter") else null
        val verse = if (args.has("verse")) args.optInt("verse") else null
        val verseEnd = if (args.has("verse_end")) args.optInt("verse_end") else null
        val results = bibleRepository.searchBibleVerse(query, book, chapter, verse, verseEnd)
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
        if (results.isNotEmpty()) {
            val first = results.first()
            val last = results.last()
            // One verse -> "Yohana 3:16"; several (range or whole chapter)
            // -> "Yohana 3:16-18" / "Zaburi 23:1-6".
            pendingReference = if (results.size == 1) {
                first.reference
            } else {
                "${first.book} ${first.chapter}:${first.verse}-${last.verse}"
            }
            pendingVerses = results.map { VerseLine(it.verse, it.text) }
        }
        appendDebug("search_bible_verse(query=$query, book=$book, chapter=$chapter, verse=$verse, verse_end=$verseEnd) -> ${results.size} result(s)")
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
            .background(Color(0xFFEAF0FB)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar: book-icon logo + title/subtitle, a small recording
            // indicator pill while a session is live, and a settings icon
            // that toggles the debug panel.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { onShowCredits() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Credits", tint = Color(0xFF0D2B52))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("VoiceSearch Bible", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D2B52))
                    Text("Sema \u00B7 Tafuta \u00B7 Soma", fontSize = 12.sp, color = Color(0xFF6B7280))
                }
                if (sessionActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(recordingPillColor(sessionState))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                RoundIconButton(icon = Icons.Filled.Keyboard, onClick = { showDebug = !showDebug })
            }

            Text(
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) \u00B7 ${statusText(sessionState, lastErrorText)}",
                color = Color(0xFF9AA3AF),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )

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

            // Result cards: one per completed voice exchange, newest at the
            // bottom -- NOT a chat-bubble transcript. If nothing has been
            // asked yet, show a simple idle hint instead of an empty list.
            val listState = rememberLazyListState()
            LaunchedEffect(cards.size) {
                if (cards.isNotEmpty()) {
                    listState.animateScrollToItem(cards.size - 1)
                }
            }
            if (cards.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Bonyeza kipaza sauti chini kuuliza mstari wa Biblia",
                        color = Color(0xFF9AA3AF),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    items(cards) { card ->
                        Column(modifier = Modifier.padding(vertical = 10.dp)) {
                            if (card.query.isNotBlank()) {
                                QueryPill(card.query)
                                Spacer(Modifier.height(10.dp))
                            }
                            ResultStatusRow(card.reference)
                            Spacer(Modifier.height(6.dp))
                            VerseResultCard(
                                card = card,
                                onListen = {
                                    val audio = card.audio ?: return@VerseResultCard
                                    coroutineScope.launch(Dispatchers.IO) {
                                        audioPlayback.start()
                                        audioPlayback.enqueue(audio)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Bottom voice control bar: keyboard/settings icon, the main
            // mic button (color-coded by session state), a short status
            // hint, and an end/close button -- replaces the chat input bar
            // look with a dedicated voice-control bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceMicButton(
                    state = sessionState,
                    onClick = { if (!sessionActive) startSession() },
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = if (sessionActive) statusText(sessionState, lastErrorText) else "Bonyeza kuzungumza...",
                    color = Color(0xFF6B7280),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                if (sessionActive) {
                    RoundIconButton(
                        icon = Icons.Filled.Close,
                        onClick = stopSession,
                        background = Color(0xFFF3F4F6),
                    )
                }
            }
        }

        // Debug panel overlay, toggled by the top-right keyboard icon --
        // kept for troubleshooting, hidden by default.
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

/** The user's spoken query, shown as a right-aligned mic pill (not a chat bubble). */
@Composable
private fun QueryPill(query: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFDCE7FB))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = Color(0xFF0D2B52), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(query, color = Color(0xFF0D2B52), fontSize = 15.sp)
        }
    }
}

/** "Nimepata matokeo! (Reference)" status row with a green checkmark. */
@Composable
private fun ResultStatusRow(reference: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(0xFF2E7D32)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Nimepata matokeo! ($reference)",
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = Color(0xFF1A1A1A),
        )
    }
}

/**
 * The verse result card: dark navy header with the reference, a yellow
 * accent strip along the whole left edge, the verse text in the body,
 * and a "Sikiliza" button that replays this card's own recorded audio.
 */
@Composable
private fun VerseResultCard(card: VerseCard, onListen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White),
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxHeight()
                .background(Color(0xFFFFC107)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D2B52))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(card.reference, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Column(modifier = Modifier.padding(16.dp)) {
                card.verses.forEachIndexed { index, line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "${line.verse}",
                            color = Color(0xFF0D2B52),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 6.dp, top = 2.dp),
                        )
                        Text(
                            text = if (card.verses.size == 1) "\u201C${line.text}\u201D" else line.text,
                            color = Color(0xFF1A1A1A),
                            fontSize = 16.sp,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                    if (index != card.verses.lastIndex) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (card.audio != null) {
                    Spacer(Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFFDCE7FB))
                                .clickable(onClick = onListen)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF0D2B52), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sikiliza", color = Color(0xFF0D2B52), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

private fun recordingPillColor(state: SessionState): Color = when (state) {
    SessionState.SPEAKING -> Color(0xFF2E7D32)
    SessionState.THINKING -> Color(0xFF7C4DFF)
    SessionState.CONNECTING -> Color(0xFF90A4AE)
    else -> Color(0xFF2196F3)
}

private fun statusText(state: SessionState, error: String?): String = when (state) {
    SessionState.IDLE -> "Tap the mic to ask for a verse"
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
 * The bottom bar's main mic button -- color-coded gradient by session
 * state (same palette the previous full-screen Orb used), pulsing while
 * connecting/listening/thinking/speaking.
 */
@Composable
private fun VoiceMicButton(state: SessionState, onClick: () -> Unit) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "mic")
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
        SessionState.ERROR -> listOf(Color(0xFFE53935), Color(0xFFFFCDD2))
        SessionState.CONNECTING -> listOf(Color(0xFF90A4AE), Color(0xFFCFD8DC))
        SessionState.SPEAKING -> listOf(Color(0xFF2962FF), Color(0xFF82B1FF))
        SessionState.LISTENING -> listOf(Color(0xFF1E88E5), Color(0xFF64B5F6))
        SessionState.THINKING -> listOf(Color(0xFF7C4DFF), Color(0xFFB39DDB))
        SessionState.IDLE -> listOf(Color(0xFF3B5FE0), Color(0xFF9DB9F5))
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(brush = Brush.verticalGradient(gradientColors))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = "Start/stop", tint = Color.White)
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
