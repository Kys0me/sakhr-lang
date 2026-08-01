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
    RETURN,         // رد
    WHILE,          // ما دام
    REPEAT,         // كرر
    FOR_EACH,       // لكل
    IN,             // في
    BREAK,          // اكفف
    CONTINUE,       // امض
    RAISE,          // بلغ
    IMPORT,         // استجلب
    FROM,           // من
    MOTHER,         // الأم
    AND,            // و
    OR,             // أو
    NOT,            // ليس
    NULL,           // فارغ
    VOID,           // عدم
    STRUCT,         // بنية
    ENUM,           // تعداد
    MATCH,          // طابق
    
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
    PLUS_EQUALS,    // +=
    MINUS_EQUALS,   // -=
    STAR_EQUALS,    // *=
    SLASH_EQUALS,   // /=
    LESS,           // <
    GREATER,        // >
    LESS_EQUALS,    // <=
    GREATER_EQUALS, // >=
    PLUS,           // +
    MINUS,          // -
    STAR,           // *
    SLASH,          // /
    PERCENT,        // %
    LEFT_PAREN,     // (
    RIGHT_PAREN,    // )
    LEFT_BRACKET,   // [
    RIGHT_BRACKET,  // ]
    QUESTION_MARK,  // ؟
    
    EOF
}

data class Token(
    val type: TokenType,
    val lexeme: String,
    val literal: Any?,
    val location: Location
)
