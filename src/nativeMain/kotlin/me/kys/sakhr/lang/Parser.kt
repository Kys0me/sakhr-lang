package me.kys.sakhr.lang

class Parser(private val tokens: List<Token>, private val diagnostics: DiagnosticEngine) {
    private var current = 0

    fun parse(): List<Stmt> {
        val statements = mutableListOf<Stmt>()
        while (!isAtEnd()) {
            val stmt = declaration()
            if (stmt != null) statements.add(stmt)
        }
        return statements
    }

    private fun declaration(): Stmt? {
        return try {
            when {
                match(TokenType.PROCEDURE) -> function("إجراء")
                match(TokenType.LET) -> letDeclaration()
                match(TokenType.CONST) -> constDeclaration()
                match(TokenType.RETURN) -> returnStatement()
                else -> statement()
            }
        } catch (_: ParseError) {
            synchronize()
            null
        }
    }

    private fun function(kind: String): Stmt {
        var receiverType: Token? = null
        var name = consume(TokenType.IDENTIFIER, "يجب تحديد اسم لل$kind.")
        
        // Handle multi-word receiver type
        if (name.lexeme == "قائمة" && check(TokenType.IDENTIFIER)) {
             val subType = advance()
             name = Token(name.type, name.lexeme + " " + subType.lexeme, name.literal, name.location)
        }

        if (match(TokenType.DOUBLE_COLON)) {
            receiverType = name
            name = consume(TokenType.IDENTIFIER, "يجب تحديد اسم الدالة بعد '::'.")
        }

        consume(TokenType.LEFT_PAREN, "يُتوقع وجود قوس '(' بعد اسم ال$kind.")
        val parameters = mutableListOf<Param>()
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                val paramName = consume(TokenType.IDENTIFIER, "يجب تحديد اسم للوسيط.")
                var paramType: Token? = null
                if (match(TokenType.COLON)) {
                    paramType = consume(TokenType.IDENTIFIER, "يجب تحديد نوع للوسيط.")
                    if (paramType.lexeme == "قائمة" && check(TokenType.IDENTIFIER)) {
                        val subType = advance()
                        paramType = Token(paramType.type, paramType.lexeme + " " + subType.lexeme, paramType.literal, paramType.location)
                    }
                }
                parameters.add(Param(paramName, paramType))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RIGHT_PAREN, "يُتوقع وجود قوس ')' بعد قائمة الوسائط.")

        var returnType: Token? = null
        if (match(TokenType.COLON)) {
            var rType = consume(TokenType.IDENTIFIER, "يجب تحديد نوع الراجع.")
            if (rType.lexeme == "قائمة" && check(TokenType.IDENTIFIER)) {
                val subType = advance()
                rType = Token(rType.type, rType.lexeme + " " + subType.lexeme, rType.literal, rType.location)
            }
            returnType = rType
        }

        consume(TokenType.BEGIN, "يُتوقع وجود الكلمة المفتاحية 'ابدأ' لبدء متن ال$kind.")
        val body = block()
        return Stmt.Function(name, receiverType, parameters, returnType, body)
    }

    private fun letDeclaration(): Stmt {
        val name = consume(TokenType.IDENTIFIER, "يجب تحديد اسم للمتغير.")
        var type: Token? = null
        if (match(TokenType.COLON)) {
            type = consume(TokenType.IDENTIFIER, "يُتوقع وجود اسم النوع بعد ':'.")
            if (type.lexeme == "قائمة" && check(TokenType.IDENTIFIER)) {
                val subType = advance()
                type = Token(type.type, type.lexeme + " " + subType.lexeme, type.literal, type.location)
            }
        }
        var initializer: Expr? = null
        if (match(TokenType.EQUALS)) {
            initializer = expression()
        }
        return Stmt.Let(name, type, initializer)
    }

    private fun constDeclaration(): Stmt {
        val name = consume(TokenType.IDENTIFIER, "يجب تحديد اسم للثابت.")
        var type: Token? = null
        if (match(TokenType.COLON)) {
            type = consume(TokenType.IDENTIFIER, "يُتوقع وجود اسم النوع بعد ':'.")
            if (type.lexeme == "قائمة" && check(TokenType.IDENTIFIER)) {
                val subType = advance()
                type = Token(type.type, type.lexeme + " " + subType.lexeme, type.literal, type.location)
            }
        }
        consume(TokenType.EQUALS, "يجب تعيين قيمة ابتدائية للثابت باستخدام '='.")
        val initializer = expression()
        return Stmt.Const(name, type, initializer)
    }

    private fun returnStatement(): Stmt {
        val keyword = previous()
        var value: Expr? = null
        // Check if there is an expression after 'رجع'. 
        // We assume an expression follows if it's not the end of a block or file.
        if (!check(TokenType.END) && !check(TokenType.ELSE)) {
            value = expression()
        }
        return Stmt.Return(keyword, value)
    }

    private fun statement(): Stmt {
        if (match(TokenType.IF)) return ifStatement()
        if (match(TokenType.BEGIN)) return Stmt.Block(block())
        return expressionStatement()
    }

    private fun ifStatement(): Stmt {
        consume(TokenType.LEFT_PAREN, "يُتوقع وجود قوس '(' بعد 'إن كان'.")
        val condition = expression()
        consume(TokenType.RIGHT_PAREN, "يُتوقع وجود قوس ')' بعد شرط 'إن كان'.")
        
        consume(TokenType.THEN, "يُتوقع وجود الكلمة المفتاحية 'إذن' بعد الشرط.")
        
        var thenBranchIsBlock = false
        val thenBranch = if (match(TokenType.BEGIN)) {
            thenBranchIsBlock = true
            Stmt.Block(block())
        } else {
            val thenStatements = mutableListOf<Stmt>()
            while (!check(TokenType.ELSE) && !check(TokenType.END) && !isAtEnd()) {
                val decl = declaration()
                if (decl != null) thenStatements.add(decl)
            }
            Stmt.Block(thenStatements)
        }
        
        var elseBranch: Stmt? = null
        var elseBranchIsBlock = false
        if (match(TokenType.ELSE)) {
            if (match(TokenType.BEGIN)) {
                elseBranchIsBlock = true
                elseBranch = Stmt.Block(block())
            } else {
                val elseStatements = mutableListOf<Stmt>()
                while (!check(TokenType.END) && !isAtEnd()) {
                    val decl = declaration()
                    if (decl != null) elseStatements.add(decl)
                }
                elseBranch = Stmt.Block(elseStatements)
            }
        }
        
        val needsEnd = if (elseBranch != null) !elseBranchIsBlock else !thenBranchIsBlock
        
        if (needsEnd) {
            consume(TokenType.END, "يُتوقع وجود 'انتهى' في نهاية كتلة 'إن كان'.")
        }

        return Stmt.If(condition, thenBranch, elseBranch)
    }

    private fun block(): List<Stmt> {
        val statements = mutableListOf<Stmt>()
        while (!check(TokenType.END) && !isAtEnd()) {
            val decl = declaration()
            if (decl != null) statements.add(decl)
        }
        consume(TokenType.END, "يُتوقع وجود 'انتهى' لإنهاء الكتلة البرمجية.")
        return statements
    }

    private fun expressionStatement(): Stmt {
        val expr = expression()
        return Stmt.Expression(expr)
    }

    private fun expression(): Expr = assignment()

    private fun assignment(): Expr {
        val expr = equality()

        if (match(TokenType.EQUALS)) {
            val equals = previous()
            val value = assignment()

            if (expr is Expr.Variable) {
                val name = expr.name
                return Expr.Assignment(name, value)
            }

            error(equals, "لا يمكن التعيين لهذه القيمة؛ هدف التعيين غير صالح.")
        }

        return expr
    }

    private fun equality(): Expr {
        var expr = comparison()
        while (match(TokenType.BANG_EQUALS, TokenType.EQUALS_EQUALS)) {
            val operator = previous()
            val right = comparison()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun comparison(): Expr {
        var expr = term()
        while (match(TokenType.GREATER, TokenType.LESS)) {
            val operator = previous()
            val right = term()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun term(): Expr {
        var expr = factor()
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            val operator = previous()
            val right = factor()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun factor(): Expr {
        var expr = unary()
        while (match(TokenType.STAR, TokenType.SLASH)) {
            val operator = previous()
            val right = unary()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun unary(): Expr = call()

    private fun call(): Expr {
        var expr = primary()
        while (true) {
            if (match(TokenType.LEFT_PAREN)) {
                expr = finishCall(expr)
            } else if (match(TokenType.DOT)) {
                val name = consume(TokenType.IDENTIFIER, "يُتوقع وجود اسم بعد النقطة '.' للوصول إلى الخاصية أو الدالة.")
                expr = Expr.Get(expr, name)
            } else {
                break
            }
        }
        return expr
    }

    private fun finishCall(callee: Expr): Expr {
        val arguments = mutableListOf<Expr>()
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                arguments.add(expression())
            } while (match(TokenType.COMMA))
        }
        val paren = consume(TokenType.RIGHT_PAREN, "يُتوقع وجود قوس ')' بعد قائمة الوسائط.")
        return Expr.Call(callee, paren, arguments)
    }

    private fun primary(): Expr {
        if (match(TokenType.BOOLEAN, TokenType.NUMBER, TokenType.STRING)) return Expr.Literal(previous().literal)
        if (match(TokenType.CONTEXT)) return Expr.Context(previous())
        if (match(TokenType.IDENTIFIER)) return Expr.Variable(previous())
        if (match(TokenType.LEFT_PAREN)) {
            val expr = expression()
            consume(TokenType.RIGHT_PAREN, "يُتوقع وجود قوس إغلاق ')' بعد التعبير.")
            return Expr.Grouping(expr)
        }
        
        val peeked = peek()
        val suggestion = if (peeked.type == TokenType.IDENTIFIER) {
            // Better to use the known Arabic keywords from Lexer, but we don't have easy access here.
            // Let's hardcode the common ones or just rely on the general error for now.
            null
        } else null

        throw error(peeked, "يُتوقع وجود تعبير أولي أو قيمة.", suggestion)
    }

    private fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw error(peek(), message)
    }

    private fun check(type: TokenType): Boolean = if (isAtEnd()) false else peek().type == type
    private fun advance(): Token {
        if (!isAtEnd()) current++
        return previous()
    }

    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF
    private fun peek(): Token = tokens[current]
    private fun previous(): Token = tokens[current - 1]

    private fun error(token: Token, message: String, suggestion: String? = null): ParseError {
        val fullMsg = if (token.type == TokenType.EOF) "$message (عند نهاية الملف)" else "$message (عند '${token.lexeme}')"
        diagnostics.report(SakhrError.SyntaxError(fullMsg, token.location, suggestion))
        return ParseError()
    }

    private fun synchronize() {
        advance()
        while (!isAtEnd()) {
            if (previous().type == TokenType.END) return
            when (peek().type) {
                TokenType.PROCEDURE, TokenType.LET, TokenType.CONST, TokenType.IF -> return
                else -> advance()
            }
        }
    }

    private class ParseError : RuntimeException()
}
