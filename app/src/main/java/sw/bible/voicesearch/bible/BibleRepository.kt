package sw.bible.voicesearch.bible

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Local, fully offline SQLite lookup for Swahili Bible verses. The
 * prebuilt database (app/src/main/assets/bible.db — built once offline
 * by scripts/build_bible_db.py, NOT shipped in the app's build path) is
 * copied byte-for-byte to app storage on first launch, matching the
 * standard Room/SQLite "prepackaged database" pattern. No network call
 * is ever made for Bible text lookups — only the voice pipeline talking
 * to Gemini Live needs network.
 *
 * Source text: Biblica(R) "Toleo Wazi Neno: Maandiko Matakatifu" (Open
 * Kiswahili Contemporary Scriptures), (c) 2018, 2024 Biblica, Inc.,
 * licensed CC BY-SA 4.0. See CreditsScreen for the full attribution
 * required by the license.
 */
class BibleRepository(context: Context) {

    private val dbFile: File = context.getDatabasePath(DB_NAME)
    private val db: SQLiteDatabase

    init {
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(DB_NAME).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied prepackaged bible.db to ${dbFile.absolutePath}")
        }
        db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    /** One matched verse result. */
    data class VerseResult(
        val book: String,
        val chapter: Int,
        val verse: Int,
        val text: String,
    ) {
        val reference: String get() = "$book $chapter:$verse"
    }

    /**
     * Backing implementation for the `search_bible_verse` tool dispatched
     * from Gemini Live's toolCall messages. Supports two modes:
     * - Exact reference lookup when [book]/[chapter] (and optionally
     *   [verse]) are given — indexed lookup on the plain `verses` table.
     * - Free-text topical search via [query] — FTS4 full-text search over
     *   verse text (e.g. "upendo", "amani", "msamaha").
     *
     * [limit] caps the number of rows returned to Gemini Live to keep the
     * toolResponse payload small.
     */
    fun searchBibleVerse(
        query: String? = null,
        book: String? = null,
        chapter: Int? = null,
        verse: Int? = null,
        limit: Int = 5,
    ): List<VerseResult> {
        return if (!book.isNullOrBlank()) {
            exactLookup(book, chapter, verse, limit)
        } else if (!query.isNullOrBlank()) {
            fullTextSearch(query, limit)
        } else {
            emptyList()
        }
    }

    private fun exactLookup(book: String, chapter: Int?, verse: Int?, limit: Int): List<VerseResult> {
        val whereParts = mutableListOf("book_name LIKE ?")
        val args = mutableListOf<String>("%$book%")
        if (chapter != null) {
            whereParts += "chapter = ?"
            args += chapter.toString()
        }
        if (verse != null) {
            whereParts += "verse = ?"
            args += verse.toString()
        }
        val cursor = db.query(
            "verses",
            arrayOf("book_name", "chapter", "verse", "text"),
            whereParts.joinToString(" AND "),
            args.toTypedArray(),
            null, null,
            "book_order ASC, chapter ASC, verse ASC",
            limit.toString(),
        )
        return cursor.use { readResults(it) }
    }

    private fun fullTextSearch(query: String, limit: Int): List<VerseResult> {
        // FTS4 MATCH against the verses_fts virtual table, joined back to
        // the plain table for book/chapter/verse metadata via rowid.
        val sql = """
            SELECT v.book_name, v.chapter, v.verse, v.text
            FROM verses_fts f
            JOIN verses v ON v.id = f.rowid
            WHERE f.text MATCH ?
            ORDER BY v.book_order ASC, v.chapter ASC, v.verse ASC
            LIMIT ?
        """.trimIndent()
        val cursor = db.rawQuery(sql, arrayOf(sanitizeFtsQuery(query), limit.toString()))
        return cursor.use { readResults(it) }
    }

    /** FTS4 chokes on bare punctuation-only tokens; strip anything that isn't a word char/space. */
    private fun sanitizeFtsQuery(query: String): String =
        query.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().ifBlank { query }

    private fun readResults(cursor: android.database.Cursor): List<VerseResult> {
        val results = mutableListOf<VerseResult>()
        while (cursor.moveToNext()) {
            results += VerseResult(
                book = cursor.getString(0),
                chapter = cursor.getInt(1),
                verse = cursor.getInt(2),
                text = cursor.getString(3),
            )
        }
        return results
    }

    companion object {
        private const val TAG = "VoiceBible/Repo"
        const val DB_NAME = "bible.db"
    }
}
