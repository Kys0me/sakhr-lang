package me.kys.sakhr.lang

data class SakhrType(val lexeme: String, val elementType: SakhrType? = null) {
    companion object {
        val NUMBER = SakhrType("رقم")
        val STRING = SakhrType("نص")
        val BOOLEAN = SakhrType("منطقي")
        val VOID = SakhrType("عدم")
        val LIST = SakhrType("قائمة")
        val UNKNOWN = SakhrType("مجهول")

        fun fromLexeme(lexeme: String): SakhrType {
            if (lexeme.startsWith("قائمة")) {
                val start = lexeme.indexOf('(')
                val end = lexeme.lastIndexOf(')')
                if (start != -1 && end != -1 && end > start) {
                    val sub = lexeme.substring(start + 1, end).trim()
                    return SakhrType("قائمة", fromLexeme(sub))
                }
                return LIST
            }
            return when (lexeme) {
                "رقم" -> NUMBER
                "نص" -> STRING
                "منطقي" -> BOOLEAN
                "عدم" -> VOID
                "قائمة" -> LIST
                else -> SakhrType(lexeme)
            }
        }
    }

    override fun toString(): String {
        return if (elementType != null) {
            "قائمة($elementType)"
        } else {
            lexeme
        }
    }
}
