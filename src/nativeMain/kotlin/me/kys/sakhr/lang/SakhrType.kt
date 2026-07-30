package me.kys.sakhr.lang

data class SakhrType(
    val lexeme: String,
    val elementType: SakhrType? = null,
    val isOptional: Boolean = false
) {
    companion object {
        val NUMBER = SakhrType("رقم")
        val STRING = SakhrType("نص")
        val BOOLEAN = SakhrType("منطقي")
        val VOID = SakhrType("عدم")
        val LIST = SakhrType("قائمة")
        val UNKNOWN = SakhrType("مجهول")
        val NULL_LITERAL = SakhrType("فارغ") // Pseudo-type for the null literal

        fun fromLexeme(lexeme: String): SakhrType {
            var text = lexeme
            var optional = false
            if (text.endsWith("؟")) {
                optional = true
                text = text.substring(0, text.length - 1)
            }

            if (text.startsWith("قائمة")) {
                val start = text.indexOf('(')
                val end = text.lastIndexOf(')')
                if (start != -1 && end != -1 && end > start) {
                    val sub = text.substring(start + 1, end).trim()
                    return SakhrType("قائمة", fromLexeme(sub), optional)
                }
                return SakhrType("قائمة", null, optional)
            }
            return when (text) {
                "رقم" -> if (optional) SakhrType("رقم", null, true) else NUMBER
                "نص" -> if (optional) SakhrType("نص", null, true) else STRING
                "منطقي" -> if (optional) SakhrType("منطقي", null, true) else BOOLEAN
                "عدم" -> if (optional) SakhrType("عدم", null, true) else VOID
                "قائمة" -> if (optional) SakhrType("قائمة", null, true) else LIST
                else -> SakhrType(text, null, optional)
            }
        }
    }

    override fun toString(): String {
        val base = if (elementType != null) {
            "قائمة($elementType)"
        } else {
            lexeme
        }
        return if (isOptional) "$base؟" else base
    }
}
