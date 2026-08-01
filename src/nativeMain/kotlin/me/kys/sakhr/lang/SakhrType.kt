package me.kys.sakhr.lang

data class SakhrType(
    val lexeme: String,
    val elementType: SakhrType? = null,
    val isOptional: Boolean = false,
    val parameterTypes: List<SakhrType>? = null,
    val returnType: SakhrType? = null
) {
    val isFunction: Boolean get() = parameterTypes != null

    companion object {
        val NUMBER = SakhrType("رقم")
        val STRING = SakhrType("نص")
        val BOOLEAN = SakhrType("منطقي")
        val VOID = SakhrType("عدم")
        val LIST = SakhrType("قائمة")
        val UNKNOWN = SakhrType("مجهول")
        val NULL_LITERAL = SakhrType("فارغ") // Pseudo-type for the null literal

        fun fromLexeme(lexeme: String): SakhrType {
            var text = lexeme.trim()
            var optional = false
            if (text.endsWith("؟")) {
                optional = true
                text = text.substring(0, text.length - 1).trim()
            }

            // Handle function types: (T1، T2) => R
            if (text.startsWith("(") && text.contains("=>")) {
                val arrowIndex = text.lastIndexOf("=>")
                val paramsPart = text.substring(1, text.lastIndexOf(")", arrowIndex)).trim()
                val returnPart = text.substring(arrowIndex + 2).trim()

                val paramTypes = if (paramsPart.isEmpty()) {
                    emptyList()
                } else {
                    splitTypes(paramsPart).map { fromLexeme(it) }
                }
                return SakhrType("دالة", null, optional, paramTypes, fromLexeme(returnPart))
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

        private fun splitTypes(text: String): List<String> {
            val result = mutableListOf<String>()
            var current = StringBuilder()
            var depth = 0
            for (char in text) {
                when (char) {
                    '(', '[' -> depth++
                    ')', ']' -> depth--
                    '،' -> if (depth == 0) {
                        result.add(current.toString().trim())
                        current = StringBuilder()
                        continue
                    }
                }
                current.append(char)
            }
            if (current.isNotEmpty()) result.add(current.toString().trim())
            return result
        }
    }

    override fun toString(): String {
        val base = when {
            parameterTypes != null && returnType != null -> {
                "(${parameterTypes.joinToString("، ")}) => $returnType"
            }
            elementType != null -> {
                "قائمة($elementType)"
            }
            else -> lexeme
        }
        return if (isOptional) "$base؟" else base
    }
}
