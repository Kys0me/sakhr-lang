package me.kys.sakhr.lang

class Lexer(private val source: String, private val diagnostics: DiagnosticEngine) {
    private val tokens = mutableListOf<Token>()
    private var start = 0
    private var current = 0
    private var line = 1
    private var column = 1

    companion object {
        val keywords = mapOf(
            "إجراء" to TokenType.PROCEDURE,
            "ليكن" to TokenType.LET,
            "ألزم" to TokenType.CONST,
            "إذن" to TokenType.THEN,
            "وإلا" to TokenType.ELSE,
            "ابدأ" to TokenType.BEGIN,
            "انتهى" to TokenType.END,
            "السياق" to TokenType.CONTEXT,
            "رد" to TokenType.RETURN,
            "كرر" to TokenType.REPEAT,
            "لكل" to TokenType.FOR_EACH,
            "في" to TokenType.IN,
            "اكفف" to TokenType.BREAK,
            "امض" to TokenType.CONTINUE,
            "بلغ" to TokenType.RAISE,
            "و" to TokenType.AND,
            "أو" to TokenType.OR,
            "ليس" to TokenType.NOT,
            "فارغ" to TokenType.NULL,
            "عدم" to TokenType.VOID,
            "بنية" to TokenType.STRUCT,
            "صح" to TokenType.BOOLEAN,
            "خطأ" to TokenType.BOOLEAN,
            // Multi-word keywords, listed here so misspelling suggestions can offer them
            "إن كان" to TokenType.IF,
            "ما دام" to TokenType.WHILE
        )
    }

    fun scanTokens(): List<Token> {
        while (!isAtEnd()) {
            start = current
            scanToken()
        }
        tokens.add(Token(TokenType.EOF, "", null, Location(line, column)))
        return tokens
    }

    private fun scanToken() {
        val c = advance()
        checkTashkeel(c)

        when (c) {
            '(' -> addToken(TokenType.LEFT_PAREN)
            ')' -> addToken(TokenType.RIGHT_PAREN)
            ':' -> {
                if (match(':')) addToken(TokenType.DOUBLE_COLON)
                else addToken(TokenType.COLON)
            }

            '.' -> addToken(TokenType.DOT)
            '،' -> addToken(TokenType.COMMA)
            '=' -> {
                if (match('=')) addToken(TokenType.EQUALS_EQUALS)
                else addToken(TokenType.EQUALS)
            }

            '<' -> {
                if (match('=')) addToken(TokenType.LESS_EQUALS)
                else addToken(TokenType.LESS)
            }

            '>' -> {
                if (match('=')) addToken(TokenType.GREATER_EQUALS)
                else addToken(TokenType.GREATER)
            }

            '+' -> {
                if (match('=')) addToken(TokenType.PLUS_EQUALS)
                else addToken(TokenType.PLUS)
            }

            '-' -> {
                if (match('=')) addToken(TokenType.MINUS_EQUALS)
                else addToken(TokenType.MINUS)
            }

            '*' -> {
                if (match('=')) addToken(TokenType.STAR_EQUALS)
                else addToken(TokenType.STAR)
            }

            '%' -> addToken(TokenType.PERCENT)
            '[' -> addToken(TokenType.LEFT_BRACKET)
            ']' -> addToken(TokenType.RIGHT_BRACKET)
            '؟' -> addToken(TokenType.QUESTION_MARK)
            '/' -> {
                if (match('/')) {
                    // A comment goes until the end of the line.
                    while (peek() != '\n' && !isAtEnd()) advance()
                } else if (match('=')) {
                    addToken(TokenType.SLASH_EQUALS)
                } else {
                    addToken(TokenType.SLASH)
                }
            }

            '!' -> {
                if (match('=')) addToken(TokenType.BANG_EQUALS)
                else diagnostics.report(
                    SakhrError.LexicalError(
                        "الرمز '!' لا يُستخدم وحده في لغة صخر.",
                        Location(line, column - 1),
                        suggestion = "استخدم '!=' للتحقق من عدم المساواة، أو الكلمة 'ليس' لنفي قيمة منطقية."
                    )
                )
            }

            ' ', '\r', '\t' -> { /* ignore whitespace */
            }

            '\n' -> {
                line++
                column = 1
            }

            '"' -> string()
            else -> {
                if (isDigit(c)) {
                    number()
                } else if (isArabicAlpha(c)) {
                    identifier()
                } else {
                    reportUnexpectedChar(c)
                }
            }
        }
    }

    /**
     * Explains what the stray character likely was instead of just echoing it;
     * most of these come from habits carried over from other languages.
     */
    private fun reportUnexpectedChar(c: Char) {
        val location = Location(line, column - 1)
        val (message, suggestion) = when (c) {
            ',' -> "الفاصلة اللاتينية ',' غير معتمدة في لغة صخر." to
                    "استخدم الفاصلة العربية '،' للفصل بين العناصر."

            '?' -> "علامة الاستفهام اللاتينية '?' غير معتمدة في لغة صخر." to
                    "استخدم علامة الاستفهام العربية '؟' للأنواع الاختيارية."

            ';' -> "الفاصلة المنقوطة ';' لا تُستخدم في لغة صخر." to
                    "احذفها؛ فلا حاجة لعلامة إنهاء في نهاية الأوامر."

            '{', '}' -> "القوس '$c' لا يُستخدم لتحديد الكتل في لغة صخر." to
                    "استخدم 'ابدأ' لفتح الكتلة و'انتهى' لإغلاقها."

            '\'' -> "علامة الاقتباس المفردة (') غير معتمدة لكتابة النصوص." to
                    "أحط النص بعلامتي اقتباس مزدوجتين \"...\" بدلاً منها."

            in 'a'..'z', in 'A'..'Z' -> "الحرف اللاتيني '$c' لا يصلح لبدء اسم أو كلمة في لغة صخر." to
                    "تُكتب الأسماء والكلمات المفتاحية بالأحرف العربية، ولكتابة نص لاتيني ضعه داخل علامتي اقتباس \"...\"."

            else -> "الرمز '$c' غير معروف في لغة صخر." to
                    "تأكد من أن الرمز مقصود، أو احذفه إن كان قد أُدخل بالخطأ."
        }
        diagnostics.report(SakhrError.LexicalError(message, location, suggestion))
    }

    private fun identifier() {
        while (isArabicAlphaNumeric(peek())) advance()

        val text = source.substring(start, current)
        // Check for multi-word keywords like "إن كان" or "ما دام"
        if (text == "إن" && peek() == ' ') {
            val potentialSpace = current
            if (source.startsWith("كان", potentialSpace + 1) &&
                !isArabicAlphaNumeric(source.getOrElse(potentialSpace + 4) { '\u0000' })
            ) {
                advance() // space
                advance(); advance(); advance() // ك ا ن
                addToken(TokenType.IF)
                return
            }
        }

        if (text == "ما" && peek() == ' ') {
            val potentialSpace = current
            if (source.startsWith("دام", potentialSpace + 1) &&
                !isArabicAlphaNumeric(source.getOrElse(potentialSpace + 4) { '\u0000' })
            ) {
                advance() // space
                advance(); advance(); advance() // د ا م
                addToken(TokenType.WHILE)
                return
            }
        }

        var type = keywords[text]
        if (type == null) type = TokenType.IDENTIFIER

        val literal = if (type == TokenType.BOOLEAN) text == "صح" else null
        addToken(type, literal)
    }

    private fun number() {
        while (isDigit(peek())) advance()

        // Look for a fractional part.
        // Support only '.' for decimals
        if (peek() == '.' && isDigit(peekNext())) {
            // Consume the '.'
            advance()

            while (isDigit(peek())) advance()
        }

        val text = source.substring(start, current)
        // Normalize Arabic-Indic digits for Double parsing
        val normalized = buildString(text.length) {
            for (ch in text) {
                append(if (ch in '\u0660'..'\u0669') (ch.code - 0x0660 + '0'.code).toChar() else ch)
            }
        }

        addToken(TokenType.NUMBER, normalized.toDouble())
    }

    private fun string() {
        // Remember where the string opened so an unterminated string points at
        // its opening quote instead of the end of the file.
        val openLine = line
        val openColumn = column - 1

        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') {
                line++
                column = 0 // advance() below brings it back to 1
            }
            advance()
        }

        if (isAtEnd()) {
            diagnostics.report(
                SakhrError.LexicalError(
                    "انتهى الملف قبل إغلاق النص الذي يبدأ هنا.",
                    Location(openLine, openColumn),
                    suggestion = "أضف علامة اقتباس \" في نهاية النص لإغلاقه."
                )
            )
            return
        }

        advance() // The closing "
        val value = source.substring(start + 1, current - 1)
        // Tashkeel is allowed in strings, so we don't check it here
        addToken(TokenType.STRING, value)
    }

    // Hoisted so the range isn't re-created on every character check.
    private val tashkeelRange = '\u064B'..'\u0652'

    private fun checkTashkeel(c: Char) {
        if (c in tashkeelRange) {
            diagnostics.report(
                SakhrError.LexicalError(
                    "علامات التشكيل غير مسموح بها في أسماء المعرفات والكلمات المفتاحية.",
                    Location(line, column - 1),
                    suggestion = "احذف علامة التشكيل؛ التشكيل مسموح به فقط داخل النصوص بين علامتي الاقتباس."
                )
            )
        }
    }

    private fun isArabicAlpha(c: Char): Boolean {
        // Arabic block: U+0600–U+06FF, excluding Tashkeel which is handled separately
        return (c in '\u0621'..'\u064A' || c == '_') && c !in tashkeelRange
    }

    private fun isArabicAlphaNumeric(c: Char): Boolean {
        return isArabicAlpha(c) || isDigit(c)
    }

    private fun isDigit(c: Char): Boolean {
        return c in '0'..'9' || c in '\u0660'..'\u0669' // Also support Arabic-Indic digits
    }

    private fun match(expected: Char): Boolean {
        if (isAtEnd()) return false
        if (source[current] != expected) return false
        current++
        column++
        return true
    }

    private fun peek(): Char = if (isAtEnd()) '\u0000' else source[current]
    private fun peekNext(): Char =
        if (current + 1 >= source.length) '\u0000' else source[current + 1]

    private fun advance(): Char {
        val c = source[current++]
        column++
        return c
    }

    private fun isAtEnd(): Boolean = current >= source.length

    private fun addToken(type: TokenType, literal: Any? = null) {
        val text = source.substring(start, current)
        tokens.add(Token(type, text, literal, Location(line, column - text.length)))
    }
}
