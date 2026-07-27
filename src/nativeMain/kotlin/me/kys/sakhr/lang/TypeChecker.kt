package me.kys.sakhr.lang

class TypeChecker(private val diagnostics: DiagnosticEngine) {
    private val scopes = mutableListOf<MutableMap<String, VariableInfo>>()
    private val functions = mutableMapOf<String, FunctionSignature>()
    private var currentFunction: FunctionSignature? = null

    enum class FunctionKind { NONE, FUNCTION, EXTENSION }
    data class VariableInfo(val type: SakhrType, val isConstant: Boolean, val isDefined: Boolean)
    data class FunctionSignature(
        val name: String,
        val params: List<SakhrType>,
        val returnType: SakhrType,
        val kind: FunctionKind,
        val receiverType: SakhrType? = null
    )

    init {
        // Built-in functions
        functions["أكتب"] =
            FunctionSignature("أكتب", listOf(SakhrType.ANY), SakhrType.VOID, FunctionKind.FUNCTION)
        functions["إنهاء_البرنامج"] = FunctionSignature(
            "إنهاء_البرنامج",
            listOf(SakhrType.NUMBER),
            SakhrType.VOID,
            FunctionKind.FUNCTION
        )
    }

    fun check(statements: List<Stmt>) {
        // First pass: collect function signatures
        for (stmt in statements) {
            if (stmt is Stmt.Function) {
                val kind =
                    if (stmt.receiverType != null) FunctionKind.EXTENSION else FunctionKind.FUNCTION
                val receiverType = stmt.receiverType?.let { SakhrType.fromLexeme(it.lexeme) }
                val sig = FunctionSignature(
                    stmt.name.lexeme,
                    stmt.params.map { SakhrType.fromLexeme(it.type.lexeme) },
                    SakhrType.fromLexeme(stmt.returnType.lexeme),
                    kind,
                    receiverType
                )
                val key =
                    if (kind == FunctionKind.EXTENSION) "${receiverType?.lexeme}::${stmt.name.lexeme}" else stmt.name.lexeme
                functions[key] = sig
            }
        }

        beginScope()
        for (stmt in statements) {
            checkStmt(stmt)
        }
        endScope()
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
                val sig = functions[key]!!
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
                    val paramType = sig.params[i]
                    declare(param.name, paramType, isConstant = false)
                    define(param.name)
                }

                stmt.body.forEach { checkStmt(it) }

                // TODO: Verify return type consistency across all paths

                endScope()
                currentFunction = enclosingFunction
            }

            is Stmt.If -> {
                val condType = checkExpr(stmt.condition)
                if (condType != SakhrType.BOOLEAN && condType != SakhrType.ANY) {
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
                val initType = stmt.initializer?.let { checkExpr(it) } ?: SakhrType.ANY
                declare(stmt.name, initType, isConstant = false)
                define(stmt.name)
            }

            is Stmt.Const -> {
                val initType = checkExpr(stmt.initializer)
                declare(stmt.name, initType, isConstant = true)
                define(stmt.name)
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
                    else -> SakhrType.ANY
                }
            }

            is Expr.Variable -> {
                val info = lookupVariable(expr.name)
                if (info == null) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "المتغير '${expr.name.lexeme}' غير معرف في هذا النطاق.",
                            expr.name.location
                        )
                    )
                    return SakhrType.ANY
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
                        SakhrType.ANY
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
                    else -> SakhrType.ANY
                }
            }

            is Expr.Call -> {
                when (val callee = expr.callee) {
                    is Expr.Variable -> {
                        val sig = functions[callee.name.lexeme]
                        if (sig == null) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "الدالة '${callee.name.lexeme}' غير معرفة.",
                                    callee.name.location
                                )
                            )
                            return SakhrType.ANY
                        }
                        validateCall(sig, expr.arguments, expr.paren)
                        sig.returnType
                    }

                    is Expr.Get -> {
                        val objType = checkExpr(callee.obj)
                        val methodName = callee.name.lexeme
                        val sig = functions["${objType.lexeme}::${methodName}"]
                        if (sig == null) {
                            // Built-in methods like 'خذ'
                            if (objType == SakhrType.LIST && methodName == "خذ") {
                                if (expr.arguments.size != 1) {
                                    diagnostics.report(
                                        SakhrError.TypeError(
                                            "الدالة 'خذ' تتوقع وسيطاً واحداً.",
                                            expr.paren.location
                                        )
                                    )
                                } else {
                                    val argType = checkExpr(expr.arguments[0])
                                    if (argType != SakhrType.NUMBER && argType != SakhrType.ANY) {
                                        diagnostics.report(
                                            SakhrError.TypeError(
                                                "وسيط الدالة 'خذ' يجب أن يكون رقماً.",
                                                getExprLocation(expr.arguments[0])
                                            )
                                        )
                                    }
                                }
                                return SakhrType.ANY
                            }
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "النوع '${objType.lexeme}' لا يحتوي على دالة ممتدة باسم '${methodName}'.",
                                    callee.name.location
                                )
                            )
                            return SakhrType.ANY
                        }
                        validateCall(sig, expr.arguments, expr.paren)
                        sig.returnType
                    }

                    else -> {
                        checkExpr(callee)
                        expr.arguments.forEach { checkExpr(it) }
                        SakhrType.ANY
                    }
                }
            }

            is Expr.Get -> {
                val objType = checkExpr(expr.obj)
                val methodName = expr.name.lexeme

                if (objType == SakhrType.LIST && methodName == "حجم") return SakhrType.NUMBER

                // If it's just a property access or method reference
                val sig = functions["${objType.lexeme}::${methodName}"]
                if (sig != null) return SakhrType.ANY // It's a callable

                diagnostics.report(
                    SakhrError.TypeError(
                        "النوع '${objType.lexeme}' لا يحتوي على خاصية باسم '${methodName}'.",
                        expr.name.location
                    )
                )
                SakhrType.ANY
            }

            is Expr.Context -> {
                if (currentFunction?.kind != FunctionKind.EXTENSION) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "لا يمكن استخدام الكلمة المفتاحية 'السياق' إلا داخل الدوال الممتدة.",
                            expr.keyword.location
                        )
                    )
                    return SakhrType.ANY
                }
                currentFunction?.receiverType ?: SakhrType.ANY
            }

            is Expr.Grouping -> checkExpr(expr.expression)
        }
    }

    private fun validateCall(sig: FunctionSignature, arguments: List<Expr>, paren: Token) {
        if (arguments.size != sig.params.size) {
            diagnostics.report(
                SakhrError.TypeError(
                    "الدالة تتوقع ${sig.params.size} وسائط، ولكن تم تمرير ${arguments.size}.",
                    paren.location
                )
            )
            return
        }
        for (i in arguments.indices) {
            val argType = checkExpr(arguments[i])
            if (!isAssignable(sig.params[i], argType)) {
                diagnostics.report(
                    SakhrError.TypeError(
                        "الوسيط رقم ${i + 1} يتوقع نوع '${sig.params[i].lexeme}' ولكن تم تمرير '${argType.lexeme}'.",
                        getExprLocation(arguments[i])
                    )
                )
            }
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
        if (target == SakhrType.ANY || source == SakhrType.ANY) return true
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
