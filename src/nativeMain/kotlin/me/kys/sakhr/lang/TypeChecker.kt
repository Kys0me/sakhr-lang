package me.kys.sakhr.lang

class TypeChecker(private val diagnostics: DiagnosticEngine) {
    private val scopes = mutableListOf<MutableMap<String, VariableInfo>>()
    private val functions = mutableMapOf<String, MutableList<FunctionSignature>>()
    private var currentFunction: FunctionSignature? = null

    enum class FunctionKind { FUNCTION, EXTENSION }
    data class VariableInfo(val type: SakhrType, val isConstant: Boolean, val isDefined: Boolean)
    data class FunctionSignature(
        val name: String,
        val params: MutableList<SakhrType>,
        val returnType: SakhrType,
        val kind: FunctionKind,
        val receiverType: SakhrType? = null
    )

    init {
        // Built-in functions
        registerBuiltIn("أكتب", listOf(SakhrType.NUMBER), SakhrType.VOID)
        registerBuiltIn("أكتب", listOf(SakhrType.STRING), SakhrType.VOID)
        registerBuiltIn("أكتب", listOf(SakhrType.BOOLEAN), SakhrType.VOID)
        registerBuiltIn("أكتب", listOf(SakhrType.LIST), SakhrType.VOID)

        registerBuiltIn("إنهاء_البرنامج", listOf(SakhrType.NUMBER), SakhrType.VOID)

        // Extension methods
        registerExtension(SakhrType.NUMBER, "نص", emptyList(), SakhrType.STRING)
        registerExtension(SakhrType.BOOLEAN, "نص", emptyList(), SakhrType.STRING)
        registerExtension(SakhrType.STRING, "نص", emptyList(), SakhrType.STRING)
        registerExtension(SakhrType.LIST, "نص", emptyList(), SakhrType.STRING)

        registerExtension(SakhrType.STRING, "طول", emptyList(), SakhrType.NUMBER)
        registerExtension(SakhrType.LIST, "حجم", emptyList(), SakhrType.NUMBER)
        registerExtension(SakhrType.LIST, "خذ", listOf(SakhrType.NUMBER), SakhrType.NUMBER)

        beginScope() // Global scope
    }

    private fun registerBuiltIn(name: String, params: List<SakhrType>, returnType: SakhrType) {
        val sig = FunctionSignature(name, params.toMutableList(), returnType, FunctionKind.FUNCTION)
        functions.getOrPut(name) { mutableListOf() }.add(sig)
    }

    private fun registerExtension(
        receiverType: SakhrType,
        name: String,
        params: List<SakhrType>,
        returnType: SakhrType
    ) {
        val sig = FunctionSignature(name, params.toMutableList(), returnType, FunctionKind.EXTENSION, receiverType)
        val key = "${receiverType.lexeme}::${name}"
        functions.getOrPut(key) { mutableListOf() }.add(sig)
    }

    fun check(statements: List<Stmt>) {
        // First pass: collect function signatures
        for (stmt in statements) {
            if (stmt is Stmt.Function) {
                val kind =
                    if (stmt.receiverType != null) FunctionKind.EXTENSION else FunctionKind.FUNCTION
                val receiverType = stmt.receiverType?.let { SakhrType.fromLexeme(it.lexeme) }
                
                val params = stmt.params.map { p ->
                    if (p.type == null) {
                        if (stmt.name.lexeme == "المطلع") SakhrType.LIST else SakhrType.UNKNOWN
                    } else {
                        SakhrType.fromLexeme(p.type.lexeme)
                    }
                }.toMutableList()
                
                val returnType = stmt.returnType?.let { SakhrType.fromLexeme(it.lexeme) } ?: SakhrType.VOID
                
                val sig = FunctionSignature(
                    stmt.name.lexeme,
                    params,
                    returnType,
                    kind,
                    receiverType
                )
                val key =
                    if (kind == FunctionKind.EXTENSION) "${receiverType?.lexeme}::${stmt.name.lexeme}" else stmt.name.lexeme
                functions.getOrPut(key) { mutableListOf() }.add(sig)
            }
        }

        for (stmt in statements) {
            checkStmt(stmt)
        }
    }

    private fun checkStmt(stmt: Stmt) {
        when (stmt) {
            is Stmt.Block -> {
                beginScope()
                stmt.statements.forEach { checkStmt(it) }
                endScope()
            }

            is Stmt.Expression -> {
                checkExpr(stmt.expression)
            }

            is Stmt.Function -> {
                val key = if (stmt.receiverType != null) {
                    "${SakhrType.fromLexeme(stmt.receiverType.lexeme).lexeme}::${stmt.name.lexeme}"
                } else {
                    stmt.name.lexeme
                }
                
                val initialParams = stmt.params.map { p ->
                    if (p.type == null) {
                        if (stmt.name.lexeme == "المطلع") SakhrType.LIST else SakhrType.UNKNOWN
                    } else {
                        SakhrType.fromLexeme(p.type.lexeme)
                    }
                }
                val returnType = stmt.returnType?.let { SakhrType.fromLexeme(it.lexeme) } ?: SakhrType.VOID
                
                // We find the signature we registered in the first pass
                val sig = functions[key]?.find { it.params == initialParams && it.returnType == returnType }
                    ?: return 

                val enclosingFunction = currentFunction
                currentFunction = sig

                beginScope()
                if (sig.kind == FunctionKind.EXTENSION) {
                    // "السياق" is available
                    scopes.last()["السياق"] =
                        VariableInfo(sig.receiverType!!, isConstant = true, isDefined = true)
                }

                for (i in stmt.params.indices) {
                    val param = stmt.params[i]
                    // Use sig.params[i] because it might have been updated by a call site 
                    // (though in this simple checker, call sites are processed in the second pass along with the body)
                    // Wait, if a call site is processed BEFORE the function body, sig.params[i] might be updated.
                    declare(param.name, sig.params[i], isConstant = false)
                    define(param.name)
                }

                stmt.body.forEach { checkStmt(it) }

                if (sig.returnType != SakhrType.VOID && !returnsOnAllPaths(stmt.body)) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "الدالة '${sig.name}' يجب أن تعيد قيمة من نوع '${sig.returnType.lexeme}'.",
                            stmt.name.location
                        )
                    )
                }

                endScope()
                currentFunction = enclosingFunction
            }

            is Stmt.If -> {
                val condType = checkExpr(stmt.condition)
                if (condType != SakhrType.BOOLEAN && condType != SakhrType.UNKNOWN) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "شرط 'إن كان' يجب أن يكون من نوع 'بولين'.",
                            getExprLocation(stmt.condition)
                        )
                    )
                }
                checkStmt(stmt.thenBranch)
                stmt.elseBranch?.let { checkStmt(it) }
            }

            is Stmt.Let -> {
                val explicitType = stmt.type?.let { SakhrType.fromLexeme(it.lexeme) }
                val initType = stmt.initializer?.let { checkExpr(it) } ?: SakhrType.VOID
                
                val finalType = if (explicitType != null) {
                    if (stmt.initializer != null && !isAssignable(explicitType, initType)) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "لا يمكن تعيين قيمة من نوع '${initType.lexeme}' لمتغير معرف كـ '${explicitType.lexeme}'.",
                                stmt.name.location
                            )
                        )
                    }
                    explicitType
                } else {
                    initType
                }
                
                declare(stmt.name, finalType, isConstant = false)
                define(stmt.name)
            }

            is Stmt.Const -> {
                val explicitType = stmt.type?.let { SakhrType.fromLexeme(it.lexeme) }
                val initType = checkExpr(stmt.initializer)
                
                val finalType = if (explicitType != null) {
                    if (!isAssignable(explicitType, initType)) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "لا يمكن تعيين قيمة من نوع '${initType.lexeme}' لثابت معرف كـ '${explicitType.lexeme}'.",
                                stmt.name.location
                            )
                        )
                    }
                    explicitType
                } else {
                    initType
                }
                
                declare(stmt.name, finalType, isConstant = true)
                define(stmt.name)
            }

            is Stmt.Return -> {
                if (currentFunction == null) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "لا يمكن استخدام 'رجع' خارج الدالة.",
                            stmt.keyword.location
                        )
                    )
                }
                val valueType = stmt.value?.let { checkExpr(it) } ?: SakhrType.VOID
                if (currentFunction != null && !isAssignable(currentFunction!!.returnType, valueType)) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "نوع الراجع '${valueType.lexeme}' لا يتطابق مع نوع إرجاع الدالة '${currentFunction!!.returnType.lexeme}'.",
                            stmt.keyword.location
                        )
                    )
                }
            }
        }
    }

    private fun checkExpr(expr: Expr): SakhrType {
        return when (expr) {
            is Expr.Literal -> {
                when (expr.value) {
                    is Double -> SakhrType.NUMBER
                    is String -> SakhrType.STRING
                    is Boolean -> SakhrType.BOOLEAN
                    null -> SakhrType.VOID
                    else -> SakhrType.UNKNOWN
                }
            }

            is Expr.Variable -> {
                val info = lookupVariable(expr.name)
                if (info == null) {
                    val allVariables = scopes.flatMap { it.keys }
                    val suggestion = DiagnosticEngine.findClosest(expr.name.lexeme, allVariables)
                    val msg = if (suggestion != null) "المتغير '${expr.name.lexeme}' غير معرف؛ هل قصدت '$suggestion'؟" 
                             else "المتغير '${expr.name.lexeme}' غير معرف في هذا النطاق."
                    
                    diagnostics.report(
                        SakhrError.TypeError(msg, expr.name.location)
                    )
                    return SakhrType.UNKNOWN
                }
                if (!info.isDefined) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "لا يمكن استخدام المتغير '${expr.name.lexeme}' قبل تهيئته.",
                            expr.name.location
                        )
                    )
                }
                info.type
            }

            is Expr.Assignment -> {
                val valueType = checkExpr(expr.value)
                val info = lookupVariable(expr.name)
                if (info == null) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "المتغير '${expr.name.lexeme}' غير معرف.",
                            expr.name.location
                        )
                    )
                } else {
                    if (info.isConstant) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "لا يمكن إعادة تعيين قيمة لـ '${expr.name.lexeme}' لأنه معرف كـ 'ألزم'.",
                                expr.name.location
                            )
                        )
                    }
                    if (!isAssignable(info.type, valueType)) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "لا يمكن تعيين قيمة من نوع '${valueType.lexeme}' لمتغير من نوع '${info.type.lexeme}'.",
                                expr.name.location
                            )
                        )
                    }
                }
                valueType
            }

            is Expr.Binary -> {
                val leftType = checkExpr(expr.left)
                val rightType = checkExpr(expr.right)
                val op = expr.operator.type

                when (op) {
                    TokenType.PLUS -> {
                        if (leftType == SakhrType.STRING || rightType == SakhrType.STRING) return SakhrType.STRING
                        if (leftType == SakhrType.NUMBER && rightType == SakhrType.NUMBER) return SakhrType.NUMBER
                        diagnostics.report(
                            SakhrError.TypeError(
                                "العملية '+' غير مدعومة بين النوعين '${leftType.lexeme}' و '${rightType.lexeme}'.",
                                expr.operator.location
                            )
                        )
                        SakhrType.UNKNOWN
                    }

                    TokenType.MINUS, TokenType.STAR, TokenType.SLASH -> {
                        if (leftType == SakhrType.NUMBER && rightType == SakhrType.NUMBER) return SakhrType.NUMBER
                        diagnostics.report(
                            SakhrError.TypeError(
                                "العملية '${expr.operator.lexeme}' تتطلب أرقاماً.",
                                expr.operator.location
                            )
                        )
                        SakhrType.NUMBER
                    }

                    TokenType.GREATER, TokenType.LESS -> {
                        if (leftType == SakhrType.NUMBER && rightType == SakhrType.NUMBER) return SakhrType.BOOLEAN
                        diagnostics.report(
                            SakhrError.TypeError(
                                "عمليات المقارنة تتطلب أرقاماً.",
                                expr.operator.location
                            )
                        )
                        SakhrType.BOOLEAN
                    }

                    TokenType.EQUALS_EQUALS, TokenType.BANG_EQUALS -> SakhrType.BOOLEAN
                    else -> SakhrType.UNKNOWN
                }
            }

            is Expr.Call -> {
                val argTypes = expr.arguments.map { checkExpr(it) }
                when (val callee = expr.callee) {
                    is Expr.Variable -> {
                        val sig = resolveAndCapture(callee.name.lexeme, argTypes)
                        if (sig == null) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "تعذر العثور على دالة باسم '${callee.name.lexeme}' تطابق هذه الوسائط.",
                                    callee.name.location
                                )
                            )
                            return SakhrType.UNKNOWN
                        }
                        sig.returnType
                    }

                    is Expr.Get -> {
                        val objType = checkExpr(callee.obj)
                        val methodName = callee.name.lexeme
                        val sig = resolveAndCapture("${objType.lexeme}::${methodName}", argTypes)
                        if (sig == null) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "النوع '${objType.lexeme}' لا يحتوي على دالة ممتدة باسم '${methodName}' تطابق هذه الوسائط.",
                                    callee.name.location
                                )
                            )
                            return SakhrType.UNKNOWN
                        }
                        sig.returnType
                    }

                    else -> {
                        checkExpr(callee)
                        SakhrType.UNKNOWN
                    }
                }
            }

            is Expr.Get -> {
                val objType = checkExpr(expr.obj)
                val propertyName = expr.name.lexeme

                // Handle properties (though we currently only have methods as extensions)
                // For now, only 'حجم' for List is a property-like access in the interpreter
                if (objType == SakhrType.LIST && propertyName == "حجم") return SakhrType.NUMBER

                diagnostics.report(
                    SakhrError.TypeError(
                        "النوع '${objType.lexeme}' لا يحتوي على خاصية باسم '${propertyName}'.",
                        expr.name.location
                    )
                )
                SakhrType.UNKNOWN
            }

            is Expr.Context -> {
                if (currentFunction?.kind != FunctionKind.EXTENSION) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "لا يمكن استخدام الكلمة المفتاحية 'السياق' إلا داخل الدوال الممتدة.",
                            expr.keyword.location
                        )
                    )
                    return SakhrType.UNKNOWN
                }
                currentFunction?.receiverType ?: SakhrType.UNKNOWN
            }

            is Expr.Grouping -> checkExpr(expr.expression)
        }
    }

    private fun resolveAndCapture(name: String, argTypes: List<SakhrType>): FunctionSignature? {
        val sigs = functions[name] ?: return null
        val sig = sigs.find { sig ->
            if (sig.params.size != argTypes.size) return@find false
            for (i in sig.params.indices) {
                if (!isAssignable(sig.params[i], argTypes[i])) return@find false
            }
            true
        }
        
        if (sig != null) {
            for (i in sig.params.indices) {
                if (sig.params[i] == SakhrType.UNKNOWN && argTypes[i] != SakhrType.UNKNOWN) {
                    sig.params[i] = argTypes[i]
                }
            }
        }
        return sig
    }

    private fun returnsOnAllPaths(statements: List<Stmt>): Boolean {
        for (stmt in statements) {
            if (returnsOnAllPaths(stmt)) return true
        }
        return false
    }

    private fun returnsOnAllPaths(stmt: Stmt): Boolean {
        return when (stmt) {
            is Stmt.Return -> true
            is Stmt.Block -> returnsOnAllPaths(stmt.statements)
            is Stmt.If -> {
                if (stmt.elseBranch == null) false
                else returnsOnAllPaths(stmt.thenBranch) && returnsOnAllPaths(stmt.elseBranch)
            }
            else -> false
        }
    }

    private fun lookupVariable(name: Token): VariableInfo? {
        for (i in scopes.size - 1 downTo 0) {
            val info = scopes[i][name.lexeme]
            if (info != null) return info
        }
        return null
    }

    private fun declare(name: Token, type: SakhrType, isConstant: Boolean) {
        if (scopes.isEmpty()) return
        val scope = scopes.last()
        if (scope.containsKey(name.lexeme)) {
            diagnostics.report(
                SakhrError.TypeError(
                    "تم تعريف الاسم '${name.lexeme}' مسبقاً في هذا النطاق.",
                    name.location
                )
            )
        }
        scope[name.lexeme] = VariableInfo(type, isConstant, false)
    }

    private fun define(name: Token) {
        if (scopes.isEmpty()) return
        val scope = scopes.last()
        val info = scope[name.lexeme]
        if (info != null) {
            scope[name.lexeme] = info.copy(isDefined = true)
        }
    }

    private fun isAssignable(target: SakhrType, source: SakhrType): Boolean {
        if (target == SakhrType.UNKNOWN || source == SakhrType.UNKNOWN) return true
        return target == source
    }

    private fun beginScope() {
        scopes.add(mutableMapOf())
    }

    private fun endScope() {
        scopes.removeAt(scopes.size - 1)
    }

    private fun getExprLocation(expr: Expr): Location {
        return when (expr) {
            is Expr.Variable -> expr.name.location
            is Expr.Binary -> expr.operator.location
            is Expr.Call -> expr.paren.location
            is Expr.Get -> expr.name.location
            is Expr.Assignment -> expr.name.location
            is Expr.Context -> expr.keyword.location
            is Expr.Grouping -> getExprLocation(expr.expression)
            else -> Location(0, 0)
        }
    }
}
