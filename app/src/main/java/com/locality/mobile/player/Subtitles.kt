package com.locality.mobile.player

import java.io.File

/**
 * Minimal WebVTT support for the player's own subtitle overlay.
 *
 * Media3 renders cues itself but exposes no timing-offset control, so — like
 * the desktop player — we parse the VTT (every subtitle Locality serves or
 * bundles is VTT) and draw the active cue in Compose. That makes the ±0.5 s
 * offset instant and vertical position a plain layout concern.
 */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** One selectable subtitle track: a server VTT URL or a downloaded local file. */
data class SubRef(val id: Long, val label: String, val url: String? = null, val path: String? = null, val lang: String? = null)

private val TIME_RE = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})""")

private fun parseTime(s: String): Long? {
    val m = TIME_RE.matchEntire(s.trim()) ?: return null
    val (h, min, sec, ms) = m.destructured
    return ((h.ifEmpty { "0" }.toLong() * 3600 + min.toLong() * 60 + sec.toLong()) * 1000) + ms.toLong()
}

/** Strip VTT/HTML-ish tags; keep the plain text (line breaks preserved). */
private fun cleanCueText(raw: String): String =
    raw.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()

fun parseVtt(content: String): List<SubtitleCue> {
    val cues = ArrayList<SubtitleCue>()
    // Blocks are separated by blank lines; a cue block contains a "-->" line.
    for (block in content.replace("\r\n", "\n").split(Regex("\n{2,}"))) {
        val lines = block.lines().filter { it.isNotBlank() }
        val timeLineIdx = lines.indexOfFirst { it.contains("-->") }
        if (timeLineIdx < 0) continue
        val parts = lines[timeLineIdx].split("-->")
        if (parts.size != 2) continue
        val start = parseTime(parts[0]) ?: continue
        // Cue settings (position, line...) may follow the end timestamp.
        val end = parseTime(parts[1].trim().substringBefore(' ')) ?: continue
        val text = cleanCueText(lines.drop(timeLineIdx + 1).joinToString("\n"))
        if (text.isNotEmpty() && end > start) cues.add(SubtitleCue(start, end, text))
    }
    return cues
}

fun parseVttFile(file: File): List<SubtitleCue> =
    runCatching { parseVtt(file.readText()) }.getOrDefault(emptyList())

/** Cues visible at [positionMs] given a user offset (positive = subs appear later). */
fun activeCueText(cues: List<SubtitleCue>, positionMs: Long, offsetMs: Long): String? {
    val t = positionMs - offsetMs
    val active = cues.filter { it.startMs <= t && t < it.endMs }
    return if (active.isEmpty()) null else active.joinToString("\n") { it.text }
}

/** Vertical placement presets, bottom-fraction of the video surface. */
enum class SubPosition(val label: String, val bottomFraction: Float) {
    Bottom("Bottom", 0.06f),
    Raised("Raised", 0.18f),
    High("High", 0.34f),
    Top("Top", 0.80f),
}
