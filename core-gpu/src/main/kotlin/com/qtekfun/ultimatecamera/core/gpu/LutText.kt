package com.qtekfun.ultimatecamera.core.gpu

import java.io.Reader

/**
 * Limits that keep a broken or hostile LUT file from exhausting memory. The lattice is allocated once from the declared
 * size, so [maxSize] bounds the parser's memory: 65 (the usual camera LUT) is 3.3 MB of floats, 129 is 25.8 MB.
 * [maxLineLength] bounds the only other buffer, the current line.
 */
data class LutParseLimits(val maxSize: Int = DEFAULT_MAX_SIZE, val maxLineLength: Int = DEFAULT_MAX_LINE_LENGTH) {
    init {
        require(maxSize in CubeLut.MIN_SIZE..CubeLut.MAX_SIZE) { "maxSize must be in ${CubeLut.MIN_SIZE}..${CubeLut.MAX_SIZE}" }
        require(maxLineLength >= MIN_LINE_LENGTH) { "maxLineLength must be at least $MIN_LINE_LENGTH" }
    }

    companion object {
        const val DEFAULT_MAX_SIZE = 129
        const val DEFAULT_MAX_LINE_LENGTH = 4096
        private const val MIN_LINE_LENGTH = 64
    }
}

/** The LUT file formats the app reads. [extension] is the lower-case file extension without the dot. */
enum class LutFormat(val extension: String) {
    CUBE("cube"),
    THREE_DL("3dl");

    companion object {
        /** The format for a file name by its extension (case-insensitive), or null if it is not a LUT file we read. */
        fun fromFileName(name: String): LutFormat? {
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return entries.firstOrNull { it.extension == ext }
        }
    }
}

/** Parses LUT text of either format from a stream, without reading the whole file into memory. */
object LutFiles {
    fun parse(format: LutFormat, reader: Reader, limits: LutParseLimits = LutParseLimits()): CubeLut = when (format) {
        LutFormat.CUBE -> CubeLutParser.parse(reader, limits)
        LutFormat.THREE_DL -> ThreeDlLutParser.parse(reader, limits)
    }
}

/**
 * Reads [reader] line by line into one reusable buffer and hands each line, without its `#` comment and surrounding
 * whitespace, split into whitespace-separated tokens, to [onLine] together with its 1-based number. Empty lines are
 * skipped. A line longer than [maxLineLength] characters fails with [CubeLutFormatException] instead of growing the
 * buffer. `\n`, `\r\n` and `\r` all end a line.
 */
internal fun forEachLutLine(reader: Reader, maxLineLength: Int, onLine: (tokens: List<String>, text: String, lineNo: Int) -> Unit) {
    val splitter = LineSplitter(maxLineLength, onLine)
    val chunk = CharArray(CHUNK)
    while (true) {
        val n = reader.read(chunk)
        if (n < 0) break
        for (i in 0 until n) splitter.accept(chunk[i])
    }
    splitter.finish()
}

private class LineSplitter(private val maxLineLength: Int, private val onLine: (List<String>, String, Int) -> Unit) {
    private val line = StringBuilder()
    private val tokens = ArrayList<String>(8)
    private var lineNo = 1
    private var previousWasCr = false

    fun accept(ch: Char) {
        val crLf = ch == '\n' && previousWasCr
        previousWasCr = ch == '\r'
        when {
            crLf -> Unit
            ch == '\n' || ch == '\r' -> {
                finish()
                line.setLength(0)
                lineNo++
            }
            line.length >= maxLineLength -> throw CubeLutFormatException("line longer than $maxLineLength characters", lineNo)
            else -> line.append(ch)
        }
    }

    fun finish() {
        emitLine(line, tokens)?.let { onLine(tokens, it, lineNo) }
    }
}

/** Fills [tokens] from [line] (comment stripped) and returns the trimmed text, or null for an empty line. */
private fun emitLine(line: StringBuilder, tokens: MutableList<String>): String? {
    tokens.clear()
    val hash = line.indexOf("#")
    val end = if (hash >= 0) hash else line.length
    var i = 0
    while (i < end) {
        while (i < end && line[i].isWhitespace()) i++
        val start = i
        while (i < end && !line[i].isWhitespace()) i++
        if (i > start) tokens.add(line.substring(start, i))
    }
    return if (tokens.isEmpty()) null else line.substring(0, end).trim()
}

private const val CHUNK = 8192
