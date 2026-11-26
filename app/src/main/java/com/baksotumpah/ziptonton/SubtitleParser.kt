package com.baksotumpah.ziptonton

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Satu baris subtitle: tampil dari startMs sampai endMs (waktu asli di file, tanpa geseran). */
class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Pembaca subtitle .srt, .vtt, .ass, .ssa menjadi daftar [Cue] terurut. */
object SubtitleParser {
    private val TIME = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val TAG_HTML = Regex("<[^>]*>")
    private val TAG_BRACE = Regex("\\{[^}]*\\}")

    /** UTF-8/UTF-16 (dengan BOM); kalau bukan UTF-8 yang valid dianggap Windows-1252 (umum di file lama). */
    fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { dec.decode(ByteBuffer.wrap(b)).toString() } catch (_: CharacterCodingException) { String(b, Charset.forName("windows-1252")) }
    }

    fun parse(fileName: String, text: String): List<Cue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val out = if (ext == "ass" || ext == "ssa") parseAss(lines) else parseTimed(lines)
        out.sortBy { it.startMs }
        return out
    }

    /** SRT dan VTT: baris waktu "00:01:02,500 --> 00:01:04,000" diikuti teks sampai baris kosong. */
    private fun parseTimed(lines: List<String>): MutableList<Cue> {
        val out = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = TIME.find(lines[i])
            if (m == null) { i++; continue }
            val start = timeMs(m.groupValues[1])
            val end = timeMs(m.groupValues[2])
            val sb = StringBuilder()
            i++
            while (i < lines.size && lines[i].isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(lines[i]); i++
            }
            val t = clean(sb.toString())
            if (t.isNotEmpty() && end > start) out += Cue(start, end, t)
        }
        return out
    }

    private fun timeMs(s: String): Long {
        val p = s.replace(',', '.').split(':')
        val sec = p.last().split('.')
        val ms = (sec.getOrElse(1) { "0" }).padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        val ss = sec[0].toLongOrNull() ?: 0L
        val mm = p.getOrNull(p.size - 2)?.toLongOrNull() ?: 0L
        val hh = if (p.size >= 3) p[p.size - 3].toLongOrNull() ?: 0L else 0L
        return ((hh * 60 + mm) * 60 + ss) * 1000 + ms
    }

    /** ASS/SSA: baris "Dialogue:" di bagian [Events], kolom diambil dari baris "Format:". */
    private fun parseAss(lines: List<String>): MutableList<Cue> {
        val out = ArrayList<Cue>()
        var inEvents = false
        var fmt: List<String>? = null
        for (raw in lines) {
            val l = raw.trim()
            if (l.startsWith("[")) { inEvents = l.equals("[Events]", ignoreCase = true); continue }
            if (!inEvents) continue
            if (l.startsWith("Format:", ignoreCase = true)) {
                fmt = l.substringAfter(':').split(',').map { it.trim().lowercase() }
            } else if (l.startsWith("Dialogue:", ignoreCase = true) && fmt != null) {
                val f = fmt
                val si = f.indexOf("start"); val ei = f.indexOf("end"); val ti = f.indexOf("text")
                if (si < 0 || ei < 0 || ti < 0) continue
                val parts = l.substringAfter(':').trimStart().split(',', limit = f.size)
                if (parts.size < f.size) continue
                val start = assTime(parts[si]); val end = assTime(parts[ei])
                val t = clean(parts[ti].replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "))
                if (t.isNotEmpty() && end > start) out += Cue(start, end, t)
            }
        }
        return out
    }

    private fun assTime(s: String): Long {   // H:MM:SS.cc
        val p = s.trim().split(':')
        if (p.size < 3) return 0L
        val sec = p[2].split('.')
        val cs = (sec.getOrElse(1) { "0" }).padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((((p[0].toLongOrNull() ?: 0L) * 60 + (p[1].toLongOrNull() ?: 0L)) * 60) + (sec[0].toLongOrNull() ?: 0L)) * 1000 + cs
    }

    private fun clean(s: String): String =
        s.replace(TAG_HTML, "").replace(TAG_BRACE, "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
