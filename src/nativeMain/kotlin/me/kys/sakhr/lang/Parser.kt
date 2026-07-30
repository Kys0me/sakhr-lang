package me.kys.sakhr.lang

class Parser(private val tokens: List<Token>, private val diagnostics: DiagnosticEngine) {
    private var current = 0

    fun parse(): List<Stmt> {
        val statements = mutableListOf<Stmt>()
        while (!isAtEnd()) {
            val stmt = topLevelDeclaration()
            if (stmt != null) statements.add(stmt)
        }
        return statements
    }

    private fun topLevelDeclaration(): Stmt? {
        return try {
            when {
                match(TokenType.PROCEDURE) -> function("إجراء")
                match(TokenType.STRUCT) -> structDeclaration()
                match(TokenType.LET) -> letDeclaration()
                match(TokenType.CONST) -> constDeclaration()
                else -> {
                    val token = peek()
                    if (token.type != TokenType.EOF) {
                        throw error(
                            token,
                            "لا يُسمح بكتابة أوامر تنفيذية خارج الدوال؛ المستوى الأعلى للملف مخصص لتعريفات 'إجراء' و'بنية' و'ليكن' و'ألزم' فقط.",
                            suggestion = "انقل هذا الكود إلى داخل دالة، مثلاً: إجراء المطلع() ابدأ ... انتهى"
                        )
                    }
                    null
                }
            }
        } catch (_: ParseError) {
            synchronize()
            null
        }
    }

    private fun declaration(): Stmt? {
        return try {
            when {
                match(TokenType.PROCEDURE) -> function("إجراء")
                match(TokenType.STRUCT) -> structDeclaration()
                match(TokenType.LET) -> letDeclaration()
                match(TokenType.CONST) -> constDeclaration()
                match(TokenType.RETURN) -> returnStatement()
                match(TokenType.RAISE) -> raiseStatement()
                else -> statement()
            }
        } catch (_: ParseError) {
            synchronize()
            null
        }
    }

    /**
     * Parses a type, handling nested list types like "قائمة(نص)" or "قائمة(قائمة(رقم))"
     * and optional types like "رقم؟".
     */
    private fun parseType(errorMessage: String): Token {
        var type = consumeType(errorMessage)
        if (type.lexeme == "قائمة" && match(TokenType.LEFT_PAREN)) {
            val innerType = parseType("يجب تحديد نوع العناصر داخل القائمة.")
            consume(TokenType.RIGHT_PAREN, "يُتوقع وجود قوس إغلاق ')' بعد نوع القائمة.")
            type = Token(type.type, "قائمة(${innerType.lexeme})", type.literal, type.location)
        }
        
        if (match(TokenType.QUESTION_MARK)) {
            type = Token(type.type, type.lexeme + "؟", type.literal, type.location)
        }
        
        return type
    }

    private fun function(kind: String): Stmt {
        var receiverType: Token? = null
        var name = consume(TokenType.IDENTIFIER, "يجب تحديد اسم لل$kind.")
        
        if (match(TokenType.DOUBLE_COLON)) {
            receiverType = name
            name = consume(TokenType.IDENTIFIER, "يجب تحديد اسم الدالة بعد '::'.")
        } else if (name.lexeme == "قائمة" && check(TokenType.LEFT_PAREN)) {
             // Handle case where receiver is a list type: قائمة(نص)::أكتب
             receiverType = parseTypeFromInitial(name)
             consume(TokenType.DOUBLE_COLON, "يُتوقع وجود '::' بعد نوع السياق.")
             name = consume(TokenType.IDENTIFIER, "يجب تحديد اسم الدالة بعد '::'.")
        }

        consume(TokenType.LEFT_PAREN, "يُتوقع وجود قوس '(' بعد اسم ال$kind.")
        val parameters = mutableListOf<Param>()
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                val paramName = consume(TokenType.IDENTIFIER, "يجب تحديد اسم للوسيط.")
                var paramType: Token? = null
                if (match(TokenType.COLON)) {
                    paramType = parseType("يجب تحديد نوع للوسيط.")
                }
                var defaultValue: Expr? = null
                if (match(TokenType.EQUALS)) {
                    defaultValue = expression()
                }
                parameters.add(Param(paramName, paramType, defaultValue))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RIGHT_PAREN, "يُتوقع وجود قوس ')' بعد قائمة الوسائط.")

        var returnType: Token? = null
        if (match(TokenType.COLON)) {
            returnType = parseType("يجب تحديد نوع الراجع.")
        }

        consume(TokenType.BEGIN, "يُتوقع وجود الكلمة المفتاحية 'ابدأ' لبدء متن ال$kind.")
        val body = block()
        return Stmt.Function(name, receiverType, parameters, returnType, body)
    }

    private fun parseTypeFromInitial(initial: Token): Token {
        if (initial.lexeme == "قائمة" && match(TokenType.LEFT_PAREN)) {
            val innerType = parseType("يجب تحديد نوع العناصر داخل القائمة.")
            consume(TokenType.RIGHT_PAREN, "يُتوقع وجود قوس إغلاق ')' بعد نوع القائمة.")
            return Token(initial.type, "قائمة(${innerType.lexeme})", initial.literal, initial.location)
        }
        return initial
    }

    private fun structDeclaration(): Stmt {
        val name = consume(TokenType.IDENTIFIER, "يجب تحديد اسم للبنية.")
        consume(TokenType.BEGIN, "يُتوقع وجود الكلمة المفتاحية 'ابدأ' لبدء تعريف البنية.")

        val fields = mutableListOf<Field>()
        while (!check(TokenType.END) && !isAtEnd()) {
            val fieldName = consume(TokenType.IDENTIFIER, "يجب تحديد اسم للحقل.")
            var fieldType: Token? = null
            if (match(TokenType.COLON)) {
                fieldType = parseType("يُتوقع وجود اسم النوع بعد ':'.")
            }
            var initializer: Expr? = null
            if (match(TokenType.EQUALS)) {
                initializer = expression()
            }
            fields.add(Field(fieldName, fieldType, initializer))
        }

        consume(TokenType.END, "يُتوقع وجود 'انتهى' لإنهاء تعريف البنية.")
        return Stmt.Struct(name, fields)
    }

    private fun letDeclaration(): Stmt {
        val names = mutableListOf<Token>()
        names.add(consume(TokenType.IDENTIFIER, "يجب تحديد اسم للمتغير."))
        while (match(TokenType.COMMA)) {
            names.add(consume(TokenType.IDENTIFIER, "يُتوقع وجود اسم متغير بعد الفاصلة."))
        }
        
        var type: Token? = null
        if (match(TokenType.COLON)) {
            type = parseType("يُتوقع وجود اسم النوع بعد ':'.")
        }
        var initializer: Expr? = null
        if (match(TokenType.EQUALS)) {
            initializer = expression()
        }
        return Stmt.Let(names, type, initializer)
    }

    private fun constDeclaration(): Stmt {
        val names = mutableListOf<Token>()
        names.add(consume(TokenType.IDENTIFIER, "يجب تحديد اسم للثابت."))
        while (match(TokenType.COMMA)) {
            names.add(consume(TokenType.IDENTIFIER, "يُتوقع وجود اسم ثابت بعد الفاصلة."))
        }
        
        var type: Token? = null
        if (match(TokenType.COLON)) {
            type = parseType("يُتوقع وجود اسم النوع بعد ':'.")
        }
        consume(
            TokenType.EQUALS,
            "الثابت يحتاج إلى قيمة ابتدائية عند تعريفه.",
            suggestion = "الثوابت المعرفة بـ'ألزم' تُعطى قيمتها مباشرة، مثال: ألزم العدد = 5"
        )
        val initializer = expression()
        return Stmt.Const(names, type, initializer)
    }

    private fun returnStatement(): Stmt {
        val keyword = previous()
        var value: Expr? = null
        // Check if there is an expression after 'رد'. 
        // We assume an expression follows if it's not the end of a block or file.
        if (!check(TokenType.END) && !check(TokenType.ELSE)) {
            value = expression()
        }
        return Stmt.Return(keyword, value)
    }

    private fun raiseStatement(): Stmt {
        val keyword = previous()
        val message = expression()
        return Stmt.Raise(keyword, message)
    }

    private fun statement(): Stmt {
        if (match(TokenType.IF)) return ifStatement()
        if (match(TokenType.WHILE)) return whileStatement()
        if (match(TokenType.FOR_EACH)) return forEachStatement()
        if (match(TokenType.BREAK)) return Stmt.Break(previous())
        if (match(TokenType.CONTINUE)) return Stmt.Continue(previous())
        if (match(TokenType.BEGIN)) return Stmt.Block(block())
        return expressionStatement()
    }

    // ما دام (شرط) كرر ... انتهى
    private fun whileStatement(): Stmt {
        consume(TokenType.LEFT_PAREN, "يُتوقع وجود قوس '(' بعد 'ما دام'.")
        val condition = expression()
        consume(TokenType.RIGHT_PAREN, "يُتوقع وجود قوس ')' بعد شرط 'ما دام'.")
        consume(TokenType.REPEAT, "يُتوقع وجود الكلمة المفتاحية 'كرر' بعد شرط 'ما دام'.")
        val body = Stmt.Block(block())
        return Stmt.While(condition, body)
    }

    // لكل (رتبة، عنصر في المجموعة) ابدأ ... انتهى
    // أو: لكل (عنصر في المجموعة) ابدأ ... انتهى
    private fun forEachStatement(): Stmt {
        consume(TokenType.LEFT_PAREN, "يُتوقع وجود قوس '(' بعد 'لكل'.")
        val first = consume(TokenType.IDENTIFIER, "يجب تحديد اسم متغير الحلقة.")
        var indexVar: Token? = null
        var elementVar = first
        if (match(TokenType.COMMA)) {
            indexVar = first
            elementVar = consume(TokenType.IDENTIFIER, "يجب تحديد اسم متغير العنصر بعد الفاصلة.")
        }
        consume(TokenType.IN, "يُتوقع وجود الكلمة المفتاحية 'في' بعد متغيرات الحلقة.")
        val iterable = expression()
        consume(TokenType.RIGHT_PAREN, "يُتوقع وجود قوس ')' بعد تعبير المجموعة.")
        consume(TokenType.BEGIN, "يُتوقع وجود الكلمة المفتاحية 'ابدأ' لبدء متن الحلقة.")
        val body = Stmt.Block(block())
        return Stmt.ForEach(indexVar, elementVar, iterable, body)
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
        consume(
            TokenType.END,
            "الكتلة البرمجية لم تُغلق بـ'انتهى'.",
            suggestion = "تأكد من أن كل 'ابدأ' تقابلها 'انتهى' في نهاية الكتلة."
        )
        return statements
    }

    private fun expressionStatement(): Stmt {
        val expr = expression()
        return Stmt.Expression(expr)
    }

    private fun expression(): Expr = assignment()

    private fun assignment(): Expr {
        val expr = or()

        if (match(TokenType.EQUALS, TokenType.PLUS_EQUALS, TokenType.MINUS_EQUALS, TokenType.STAR_EQUALS, TokenType.SLASH_EQUALS)) {
            val operator = previous()
            val value = assignment()

            if (expr is Expr.Variable) {
                val name = expr.name
                
                if (operator.type == TokenType.EQUALS) {
                    return Expr.Assignment(name, value)
                }

                // Desugar compound assignments: a += b -> a = a + b
                val binaryType = when (operator.type) {
                    TokenType.PLUS_EQUALS -> TokenType.PLUS
                    TokenType.MINUS_EQUALS -> TokenType.MINUS
                    TokenType.STAR_EQUALS -> TokenType.STAR
                    TokenType.SLASH_EQUALS -> TokenType.SLASH
                    else -> throw error(operator, "عملية تعيين غير معروفة.")
                }
                
                val binaryOp = Token(binaryType, operator.lexeme.substring(0, operator.lexeme.length - 1), null, operator.location)
                val desugaredValue = Expr.Binary(expr, binaryOp, value)
                return Expr.Assignment(name, desugaredValue)
            } else if (expr is Expr.Get) {
                if (operator.type == TokenType.EQUALS) {
                    return Expr.Set(expr.obj, expr.name, value)
                }
                
                // Desugar compound assignments for properties: o.f += v -> o.f = o.f + v
                val binaryType = when (operator.type) {
                    TokenType.PLUS_EQUALS -> TokenType.PLUS
                    TokenType.MINUS_EQUALS -> TokenType.MINUS
                    TokenType.STAR_EQUALS -> TokenType.STAR
                    TokenType.SLASH_EQUALS -> TokenType.SLASH
                    else -> throw error(operator, "عملية تعيين غير معروفة.")
                }
                val binaryOp = Token(binaryType, operator.lexeme.substring(0, operator.lexeme.length - 1), null, operator.location)
                val desugaredValue = Expr.Binary(expr, binaryOp, value)
                return Expr.Set(expr.obj, expr.name, desugaredValue)
            } else if (expr is Expr.Index) {
                // Assigning to list elements directly (by index) is not supported;
                // callers should use the 'استبدل' extension instead.
                throw error(
                    operator,
                    "التعيين المباشر لعنصر في قائمة عبر الفهرس غير مدعوم.",
                    suggestion = "استخدم الدالة الممتدة 'استبدل'، مثال: القائمة.استبدل(الفهرس، القيمة_الجديدة)"
                )
            }

            // Reaching here means the target is not assignable (the valid
            // Variable/Get cases return above); abort so 'synchronize' can recover.
            throw error(
                operator,
                "الطرف الأيسر لعملية التعيين ليس موضعاً صالحاً لتخزين قيمة.",
                suggestion = "يمكن التعيين فقط لمتغير أو لحقل في بنية، وللمقارنة بين قيمتين استخدم '==' بدلاً من '='."
            )
        }

        return expr
    }

    private fun or(): Expr {
        var expr = and()
        while (match(TokenType.OR)) {
            val operator = previous()
            val right = and()
            expr = Expr.Logical(expr, operator, right)
        }
        return expr
    }

    private fun and(): Expr {
        var expr = equality()
        while (match(TokenType.AND)) {
            val operator = previous()
            val right = equality()
            expr = Expr.Logical(expr, operator, right)
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
        while (match(
                TokenType.GREATER, TokenType.GREATER_EQUALS,
                TokenType.LESS, TokenType.LESS_EQUALS
        )) {
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
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            val operator = previous()
            val right = unary()
            expr = Expr.Binary(expr, operator, right)
        }
        return expr
    }

    private fun unary(): Expr {
        if (match(TokenType.NOT, TokenType.MINUS)) {
            val operator = previous()
            val right = unary()
            return Expr.Unary(operator, right)
        }
        return call()
    }

    private fun call(): Expr {
        var expr = primary()
        while (true) {
            if (match(TokenType.LEFT_PAREN)) {
                expr = finishCall(expr)
            } else if (match(TokenType.DOT)) {
                val name = consume(TokenType.IDENTIFIER, "يُتوقع وجود اسم بعد النقطة '.' للوصول إلى الخاصية أو الدالة.")
                expr = Expr.Get(expr, name)
            } else if (match(TokenType.LEFT_BRACKET)) {
                val bracket = previous()
                val index = expression()
                consume(TokenType.RIGHT_BRACKET, "يُتوقع وجود قوس إغلاق ']' بعد الفهرس.")
                expr = Expr.Index(expr, bracket, index)
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
        if (match(TokenType.BOOLEAN, TokenType.NUMBER, TokenType.STRING)) return Expr.Literal(previous().literal, previous().location)
        if (match(TokenType.NULL)) return Expr.Literal(null, previous().location)
        if (match(TokenType.VOID)) return Expr.Literal(SakhrUnit, previous().location)
        if (match(TokenType.CONTEXT)) return Expr.Context(previous())
        if (match(TokenType.IDENTIFIER)) return Expr.Variable(previous())
        if (match(TokenType.LEFT_BRACKET)) return listLiteral()
        if (match(TokenType.LEFT_PAREN)) {
            val expr = expression()
            consume(TokenType.RIGHT_PAREN, "يُتوقع وجود قوس إغلاق ')' بعد التعبير.")
            return Expr.Grouping(expr)
        }

        throw error(
            peek(),
            "كان من المتوقع وجود تعبير هنا (قيمة، اسم متغير، أو استدعاء دالة)."
        )
    }

    // قائمة حرفية: [عنصر1، عنصر2، ...]
    private fun listLiteral(): Expr {
        val bracket = previous()
        val elements = mutableListOf<Expr>()
        if (!check(TokenType.RIGHT_BRACKET)) {
            do {
                elements.add(expression())
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RIGHT_BRACKET, "يُتوقع وجود قوس إغلاق ']' بعد عناصر القائمة.")
        return Expr.ListLiteral(bracket, elements)
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

    private fun consume(type: TokenType, message: String, suggestion: String? = null): Token {
        if (check(type)) return advance()
        throw error(peek(), message, suggestion)
    }

    private fun consumeType(message: String): Token {
        if (check(TokenType.IDENTIFIER) || check(TokenType.VOID)) return advance()
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
        val fullMsg = if (token.type == TokenType.EOF) {
            "$message وصل المفسر إلى نهاية الملف قبل اكتمال الجملة."
        } else {
            "$message وُجد '${token.lexeme}' بدلاً من ذلك."
        }
        // If no explicit hint exists and the stray token is an identifier, it may
        // be a misspelled keyword; offer the closest match.
        val finalSuggestion = suggestion ?: run {
            if (token.type == TokenType.IDENTIFIER) {
                DiagnosticEngine.findClosest(token.lexeme, Lexer.keywords.keys)
                    ?.let { "هل قصدت الكلمة المفتاحية '$it'؟" }
            } else null
        }
        diagnostics.report(
            SakhrError.SyntaxError(
                fullMsg,
                token.location,
                finalSuggestion,
                length = maxOf(1, token.lexeme.length)
            )
        )
        return ParseError()
    }

    private fun synchronize() {
        advance()
        while (!isAtEnd()) {
            if (previous().type == TokenType.END) return
            when (peek().type) {
                TokenType.PROCEDURE, TokenType.LET, TokenType.CONST,
                TokenType.IF, TokenType.WHILE, TokenType.FOR_EACH,
                TokenType.RETURN, TokenType.RAISE -> return
                else -> advance()
            }
        }
    }

    private class ParseError : RuntimeException()
}
