package com.sinesislab.livenotes.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One transcript line, timestamped to when it was SPOKEN (VAD side). */
data class TsLine(val t: String, val text: String)

/**
 * Sessions, mirroring the desktop app's on-disk layout so a later sync path
 * can hand files over as-is:
 *
 *   filesDir/sessions.json          index: [{slug, title, createdAt, updatedAt}]
 *   filesDir/sessions/<slug>/
 *     transcript.md                 "- [HH:MM] text" per utterance
 *     latest.txt                    plain-text mirror
 *     autonotes.md                  the AI's growing live-notes bullets
 *     analysis.md + analysis-N.md   re-analysis runs (runs are KEPT; .md is the latest)
 *     recording.wav               the session's audio, streamed whole-session
 */
class SessionStore(context: Context) {
    private val root = File(context.filesDir, "data")
    private val sessionsDir = File(root, "sessions")
    private val indexFile = File(root, "sessions.json")
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    data class Meta(
        val slug: String,
        val title: String,
        val createdAt: String,
        val updatedAt: String,
        val language: String = "", // per-session STT language; blank = the device default
        val topic: String = "", // the session's topic — the AI's session context (NOT the display title)
    )

    private var index: List<MutableMap<String, String>> = loadIndex()

    private fun loadIndex(): List<MutableMap<String, String>> =
        try {
            JSONArray(indexFile.readText()).let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    mutableMapOf(
                        "slug" to o.getString("slug"),
                        "title" to o.getString("title"),
                        "createdAt" to o.getString("createdAt"),
                        "updatedAt" to o.getString("updatedAt"),
                        "language" to o.optString("language", ""),
                        "topic" to o.optString("topic", ""),
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }

    private fun saveIndex() {
        root.mkdirs()
        val arr = JSONArray()
        index.forEach { m ->
            arr.put(
                JSONObject()
                    .put("slug", m["slug"])
                    .put("title", m["title"])
                    .put("createdAt", m["createdAt"])
                    .put("updatedAt", m["updatedAt"])
                    .put("language", m["language"] ?: "")
                    .put("topic", m["topic"] ?: "")
            )
        }
        indexFile.writeText(arr.toString(2))
    }

    val all: List<Meta>
        get() = index.map {
            Meta(it["slug"]!!, it["title"]!!, it["createdAt"]!!, it["updatedAt"]!!, it["language"] ?: "", it["topic"] ?: "")
        }.sortedByDescending { it.updatedAt }

    /** Create a session; slugs are unique by construction (slug-2, slug-3…).
     *  Topic and language ride with the session; nothing global is implied. */
    fun create(title: String, language: String = "", topic: String = ""): Meta {
        val t = title.ifBlank { "Session ${stamp.format(Date())}" }
        val now = isoNow()
        val slug = uniqueSlug(slugify(t))
        val meta = Meta(slug, t, now, now, language, topic)
        index = index + mutableMapOf(
            "slug" to slug,
            "title" to t,
            "createdAt" to now,
            "updatedAt" to now,
            "language" to language,
            "topic" to topic,
        )
        saveIndex()
        File(sessionsDir, slug).mkdirs()
        File(sessionsDir, "$slug/transcript.md").writeText("")
        File(sessionsDir, "$slug/latest.txt").writeText("")
        return meta
    }

    fun get(slug: String): Meta? = all.firstOrNull { it.slug == slug }

    /** Post-hoc rename: title only — the slug (and its directory, and every
     *  file path) stays as the id; list cards pick the new title up. */
    fun rename(slug: String, title: String) {
        val t = title.trim()
        if (t.isBlank()) return
        index = index.map {
            if (it["slug"] == slug) {
                it["title"] = t
                it["updatedAt"] = isoNow()
                it
            } else {
                it
            }
        }
        saveIndex()
    }

    fun delete(slug: String) {
        index = index.filter { it["slug"] != slug }
        saveIndex()
        File(sessionsDir, slug).deleteRecursively()
    }

    fun appendLine(slug: String, text: String, spokenAtMs: Long): TsLine {
        val f = File(sessionsDir, "$slug/transcript.md")
        // the stamp is WHEN THE WORDS WERE SPOKEN (VAD side) — the same
        // contract as the desktop; transcription lag never shifts lines.
        // Returns the stamped line so callers can keep the live list without
        // re-parsing the file.
        val line = TsLine(time.format(Date(spokenAtMs)), text)
        f.appendText("- [${line.t}] ${line.text}\n")
        File(sessionsDir, "$slug/latest.txt").appendText(text + "\n")
        touch(slug)
        return line
    }

    fun transcriptLines(slug: String): List<TsLine> =
        try {
            // per-line parse (the desktop's loop, not a whole-file ^…$ match:
            // JVM anchors match only string start/end without MULTILINE — the
            // whole-pane-empties-on-the-second-utterance bug)
            val pattern = Regex("^- \\[(\\d{2}:\\d{2})\\] (.*)$")
            File(sessionsDir, "$slug/transcript.md").readText().lineSequence().mapNotNull { line ->
                pattern.matchEntire(line.trim())?.let { TsLine(it.groupValues[1], it.groupValues[2]) }
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }

    fun transcriptText(slug: String): String = transcriptLines(slug).joinToString("\n") { it.text }

    val autonotesFile: (String) -> File = { slug -> File(sessionsDir, "$slug/autonotes.md") }

    /** The session's audio itself — the capture service streams the whole
     *  session here ("record the voices", not just transcribe them). */
    fun recordingFile(slug: String): File = File(sessionsDir, "$slug/recording.wav")

    fun loadAutonotes(slug: String): String = try {
        autonotesFile(slug).readText().trim()
    } catch (e: Exception) {
        ""
    }

    fun saveAutonotes(slug: String, content: String) {
        File(sessionsDir, slug).mkdirs()
        autonotesFile(slug).writeText(content.trim().ifBlank { "" } + if (content.isBlank()) "" else "\n")
        touch(slug)
    }

    fun loadAnalysis(slug: String): Pair<Int, String> {
        val dir = File(sessionsDir, slug)
        val runs = dir.list { _, n -> Regex("^analysis-\\d+\\.md$").matches(n) }
            ?.mapNotNull { Regex("-(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
            ?.sorted() ?: emptyList()
        val latest = runs.lastOrNull() ?: return 0 to ""
        return runs.size to File(dir, "analysis-$latest.md").readText().trim()
    }

    fun saveAnalysis(slug: String, content: String): Int {
        val dir = File(sessionsDir, slug)
        dir.mkdirs()
        val (count, _) = loadAnalysis(slug)
        val n = count + 1
        File(dir, "analysis-$n.md").writeText(content.trim() + "\n")
        File(dir, "analysis.md").writeText(content.trim() + "\n")
        touch(slug)
        return n
    }

    private fun touch(slug: String) {
        index = index.map {
            if (it["slug"] == slug) {
                it["updatedAt"] = isoNow()
                it
            } else {
                it
            }
        }
        saveIndex()
    }

    private fun slugify(t: String): String =
        StringBuilder().also { sb ->
            for (c in t.lowercase()) {
                val d = if (c.isLetterOrDigit() && c.code < 128) c else '-'
                // collapse dash runs the way the desktop slugify's regex does
                if (!(d == '-' && sb.isNotEmpty() && sb.last() == '-')) sb.append(d)
            }
        }.toString().trim('-').take(60).ifBlank { "session" }

    private fun uniqueSlug(base: String): String {
        val taken = index.map { it["slug"] }.toSet()
        var slug = base
        var n = 2
        while (slug in taken) slug = "$base-${n++}"
        return slug
    }

    private fun isoNow(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
}