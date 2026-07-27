package me.kys.sakhr.lang

enum class SakhrType(val lexeme: String) {
    NUMBER("رقم"),
    STRING("نص"),
    BOOLEAN("منطقي"),
    VOID("عدم"),
    LIST("قائمة"),
    ANY("أي");

    companion object {
        fun fromLexeme(lexeme: String): SakhrType {
            if (lexeme.startsWith("قائمة")) return LIST
            return entries.find { it.lexeme == lexeme } ?: VOID
        }
    }
}