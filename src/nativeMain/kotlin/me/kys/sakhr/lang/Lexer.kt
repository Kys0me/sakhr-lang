package me.kys.sakhr.lang

class Lexer(private val source: String, private val diagnostics: DiagnosticEngine) {
    private val tokens = mutableListOf<Token>()
    private var start = 0
    private var current = 0
    private var line = 1
    private var column = 1

    private val keywords = mapOf(
        "إجراء" to TokenType.PROCEDURE,
        "ليكن" to TokenType.LET,
        "ألزم" to TokenType.CONST,
        "إن كان" to TokenType.IF, // Special case for spaces in keywords? No, usually keywords are single words.
        "إذن" to TokenType.THEN,
        "وإلا" to TokenType.ELSE,
        "ابدأ" to TokenType.BEGIN,
        "انتهى" to TokenType.END,
        "السياق" to TokenType.CONTEXT,
        "صح" to TokenType.BOOLEAN,
        "خطأ" to TokenType.BOOLEAN
    )

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
            '<' -> addToken(TokenType.LESS)
            '>' -> addToken(TokenType.GREATER)
            '+' -> addToken(TokenType.PLUS)
            '-' -> addToken(TokenType.MINUS)
            '*' -> addToken(TokenType.STAR)
            '/' -> {
                if (match('/')) {
                    // A comment goes until the end of the line.
                    while (peek() != '\n' && !isAtEnd()) advance()
                } else {
                    addToken(TokenType.SLASH)
                }
            }
            '!' -> {
                if (match('=')) addToken(TokenType.BANG_EQUALS)
                else diagnostics.report(SakhrError.LexicalError("رمز غير صالح: '!'", Location(line, column)))
            }
            ' ' , '\r', '\t' -> { /* ignore whitespace */ }
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
                    diagnostics.report(SakhrError.LexicalError("رمز غير صالح: '$c'", Location(line, column)))
                }
            }
        }
    }

    private fun identifier() {
        while (isArabicAlphaNumeric(peek())) advance()
        
        val text = source.substring(start, current)
        // Check for multi-word keywords like "إن كان"
        if (text == "إن" && peek() == ' ' && source.substring(current + 1).startsWith("كان")) {
             advance() // space
             advance(); advance(); advance() // ك ا ن
             addToken(TokenType.IF)
             return
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
        val normalized = text.map {
            if (it in '\u0660'..'\u0669') {
                (it.code - 0x0660 + '0'.code).toChar()
            } else {
                it
            }
        }.joinToString("")

        addToken(TokenType.NUMBER, normalized.toDouble())
    }

    private fun string() {
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') line++
            advance()
        }

        if (isAtEnd()) {
            diagnostics.report(SakhrError.LexicalError("نص غير منتهٍ؛ يتوقع وجود علامة اقتباس في نهاية النص.", Location(line, column)))
            return
        }

        advance() // The closing "
        val value = source.substring(start + 1, current - 1)
        // Tashkeel is allowed in strings, so we don't check it here
        addToken(TokenType.STRING, value)
    }

    private fun checkTashkeel(c: Char) {
        val tashkeelRange = '\u064B'..'\u0652'
        if (c in tashkeelRange) {
            diagnostics.report(SakhrError.LexicalError("يمنع استخدام علامات التشكيل خارج النصوص الصريحة.", Location(line, column)))
        }
    }

    private fun isArabicAlpha(c: Char): Boolean {
        // Arabic block: U+0600–U+06FF
        return c in '\u0621'..'\u064A' || c == '_'
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
    private fun peekNext(): Char = if (current + 1 >= source.length) '\u0000' else source[current + 1]

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
