package me.kys.sakhr.lang

import kotlin.math.min

data class Location(val line: Int, val column: Int)

sealed class SakhrError(msg: String, val location: Location, val suggestion: String? = null) :
    RuntimeException(msg) {
    class LexicalError(message: String, location: Location, suggestion: String? = null) :
        SakhrError("خطأ معجمي: $message", location, suggestion)

    class SyntaxError(message: String, location: Location, suggestion: String? = null) :
        SakhrError("خطأ نحوي: $message", location, suggestion)

    class TypeError(message: String, location: Location, suggestion: String? = null) :
        SakhrError("خطأ دلالي: $message", location, suggestion)

    class RuntimeError(message: String, location: Location, suggestion: String? = null) :
        SakhrError("خطأ تنفيذي: $message", location, suggestion)
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

        val header = if (fileName != null) {
            "في الملف '$fileName'، السطر ${error.location.line}، العمود ${error.location.column}:"
        } else {
            "في السطر ${error.location.line}، العمود ${error.location.column}:"
        }

        println("\n$header")
        println(error.message)

        source?.let { src ->
            val lines = src.split('\n')
            if (error.location.line <= lines.size) {
                val lineText = lines[error.location.line - 1]
                println("  |")
                println("${error.location.line.toString().padStart(3)} | $lineText")
                println("  | ${" ".repeat(error.location.column - 1)}^")
            }
        }

        error.suggestion?.let {
            println("💡 اقتراح: $it")
        }
    }

    fun hasErrors() = errors.isNotEmpty()

    fun clear() {
        errors.clear()
    }

    companion object {
        fun findClosest(target: String, candidates: Collection<String>): String? {
            if (candidates.isEmpty()) return null
            return candidates.minByOrNull { levenshtein(target, it) }
                ?.takeIf { levenshtein(target, it) <= 2 }
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
