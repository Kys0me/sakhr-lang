package me.kys.sakhr.lang

import kotlin.math.min

data class Location(val line: Int, val column: Int)

sealed class SakhrError(
    message: String,
    val location: Location,
    val suggestion: String? = null,
    val length: Int = 1
) : RuntimeException(message) {
    abstract val kind: String

    class LexicalError(
        message: String,
        location: Location,
        suggestion: String? = null,
        length: Int = 1
    ) : SakhrError(message, location, suggestion, length) {
        override val kind: String get() = "خطأ معجمي"
    }

    class SyntaxError(
        message: String,
        location: Location,
        suggestion: String? = null,
        length: Int = 1
    ) : SakhrError(message, location, suggestion, length) {
        override val kind: String get() = "خطأ نحوي"
    }

    class TypeError(
        message: String,
        location: Location,
        suggestion: String? = null,
        length: Int = 1
    ) : SakhrError(message, location, suggestion, length) {
        override val kind: String get() = "خطأ دلالي"
    }

    class RuntimeError(
        message: String,
        location: Location,
        suggestion: String? = null,
        length: Int = 1
    ) : SakhrError(message, location, suggestion, length) {
        override val kind: String get() = "خطأ أثناء التنفيذ"
    }
}

class DiagnosticEngine {
    private val errors = mutableListOf<SakhrError>()
    private var source: String? = null
    private var fileName: String? = null

    fun setSource(source: String, fileName: String? = null) {
        this.source = source
        this.fileName = fileName
    }

    fun report(error: SakhrError) {
        errors.add(error)

        println()
        println("${error.kind}: ${error.message}")
        println(locationLine(error.location))

        source?.let { src ->
            val snippet = renderSnippet(src, error)
            if (snippet != null) {
                println()
                println(snippet)
            }
        }

        error.suggestion?.let {
            println()
            println("اقتراح: $it")
        }
    }

    private fun locationLine(location: Location): String {
        if (location.line <= 0) {
            return if (fileName != null) "في الملف '$fileName' (موضع غير محدد)"
            else "في موضع غير محدد"
        }
        return buildString {
            append("في ")
            fileName?.let { append("الملف '$it'، ") }
            append("السطر ${location.line}، العمود ${location.column}")
        }
    }

    /**
     * Renders the offending source line with a gutter sized to the line
     * number, and a caret line that mirrors the tabs of the source line so
     * the marker stays aligned regardless of indentation.
     */
    private fun renderSnippet(src: String, error: SakhrError): String? {
        val lines = src.split('\n')
        if (error.location.line !in 1..lines.size) return null

        val lineText = lines[error.location.line - 1].trimEnd('\r')
        val lineNum = error.location.line.toString()
        val pad = " ".repeat(lineNum.length)

        // Clamp so errors at (or past) the end of the line still point sensibly.
        val column = error.location.column.coerceIn(1, lineText.length + 1)
        val prefix = buildString(column - 1) {
            for (i in 0 until column - 1) {
                append(if (i < lineText.length && lineText[i] == '\t') '\t' else ' ')
            }
        }
        val span = error.length.coerceIn(1, maxOf(1, lineText.length - column + 1))

        return buildString {
            append(" $pad |\n")
            append(" $lineNum | $lineText\n")
            append(" $pad | $prefix${"^".repeat(span)}")
        }
    }

    fun hasErrors() = errors.isNotEmpty()

    fun errorCount() = errors.size

    /** Arabic-pluralized summary of how many errors were reported. */
    fun summary(): String {
        return when (val n = errors.size) {
            0 -> "لم يُعثر على أي أخطاء."
            1 -> "تم العثور على خطأ واحد."
            2 -> "تم العثور على خطأين."
            in 3..10 -> "تم العثور على $n أخطاء."
            else -> "تم العثور على $n خطأً."
        }
    }

    fun clear() {
        errors.clear()
    }

    companion object {
        fun findClosest(target: String, candidates: Collection<String>): String? {
            if (candidates.isEmpty()) return null
            // Short names tolerate fewer edits; a distance of 2 on a
            // three-letter word is usually a different word entirely.
            val threshold = if (target.length <= 3) 1 else 2
            var best: String? = null
            var bestDistance = threshold + 1
            for (candidate in candidates) {
                if (candidate == target) continue
                val distance = levenshtein(target, candidate)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = candidate
                }
            }
            return best
        }

        private fun levenshtein(s1: String, s2: String): Int {
            val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
            for (i in 0..s1.length) dp[i][0] = i
            for (j in 0..s2.length) dp[0][j] = j
            for (i in 1..s1.length) {
                for (j in 1..s2.length) {
                    val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                    dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
                }
            }
            return dp[s1.length][s2.length]
        }
    }
}
