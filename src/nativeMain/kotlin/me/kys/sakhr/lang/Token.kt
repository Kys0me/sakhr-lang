package me.kys.sakhr.lang

enum class TokenType {
    // Keywords
    PROCEDURE,      // إجراء
    LET,            // ليكن
    CONST,          // ألزم
    IF,             // إن كان
    THEN,           // إذن
    ELSE,           // وإلا
    BEGIN,          // ابدأ
    END,            // انتهى
    CONTEXT,        // السياق
    
    // Literals
    IDENTIFIER,
    STRING,
    NUMBER,
    BOOLEAN,
    
    // Punctuation & Operators
    DOUBLE_COLON,   // ::
    DOT,            // .
    COLON,          // :
    COMMA,          // ، (Arabic comma)
    EQUALS,         // =
    EQUALS_EQUALS,  // ==
    BANG_EQUALS,    // !=
    LESS,           // <
    GREATER,        // >
    PLUS,           // +
    MINUS,          // -
    STAR,           // *
    SLASH,          // /
    LEFT_PAREN,     // (
    RIGHT_PAREN,    // )
    
    EOF
}

data class Token(
    val type: TokenType,
    val lexeme: String,
    val literal: Any?,
    val location: Location
)
