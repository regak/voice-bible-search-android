package sw.bible.voicesearch.realtime

/** Common contract for a Gemini Live speech-to-speech backend. */
interface VoiceBackend {
    fun connect()
    fun sendAudioChunk(pcm16: ShortArray)
    fun cancelResponse()
    fun disconnect()
}
