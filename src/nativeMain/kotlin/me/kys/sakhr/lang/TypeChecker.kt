package me.kys.sakhr.lang

class TypeChecker(private val diagnostics: DiagnosticEngine) {
    private val scopes = mutableListOf<Scope>()
    private var currentFunction: FunctionSignature? = null
    private var loopDepth = 0

    class Scope {
        val variables = mutableMapOf<String, VariableInfo>()
        val functions = mutableMapOf<String, MutableList<FunctionSignature>>()
        val structs = mutableMapOf<String, StructInfo>()
    }

    enum class FunctionKind { FUNCTION, EXTENSION }
    data class VariableInfo(val type: SakhrType, val isConstant: Boolean, val isDefined: Boolean)
    data class StructInfo(val name: String, val fields: MutableMap<String, SakhrType>)
    data class FunctionSignature(
        val name: String,
        val params: MutableList<SakhrType>,
        val returnType: SakhrType,
        val kind: FunctionKind,
        val receiverType: SakhrType? = null,
        val paramNames: List<String> = emptyList(),
        val isParamRequired: List<Boolean> = emptyList()
    )

    init {
        beginScope() // Global scope
        
        // Built-in functions
        registerBuiltIn("أكتب", listOf(SakhrType.NUMBER), SakhrType.VOID)
        registerBuiltIn("أكتب", listOf(SakhrType.STRING), SakhrType.VOID)
        registerBuiltIn("أكتب", listOf(SakhrType.BOOLEAN), SakhrType.VOID)
        registerBuiltIn("أكتب", listOf(SakhrType.LIST), SakhrType.VOID)

        registerBuiltIn("إنهاء_البرنامج", listOf(SakhrType.NUMBER), SakhrType.VOID)
        registerBuiltIn("اقرأ", emptyList(), SakhrType.STRING)
        registerBuiltIn("رقم", listOf(SakhrType.UNKNOWN), SakhrType.NUMBER)
        registerBuiltIn("نص", listOf(SakhrType.UNKNOWN), SakhrType.STRING)
        registerBuiltIn("منطقي", listOf(SakhrType.UNKNOWN), SakhrType.BOOLEAN)

        // Extension methods
        registerExtension(SakhrType.NUMBER, "نص", emptyList(), SakhrType.STRING)
        registerExtension(SakhrType.BOOLEAN, "نص", emptyList(), SakhrType.STRING)
        registerExtension(SakhrType.STRING, "نص", emptyList(), SakhrType.STRING)
        registerExtension(SakhrType.LIST, "نص", emptyList(), SakhrType.STRING)

        registerExtension(SakhrType.STRING, "طول", emptyList(), SakhrType.NUMBER)
        registerExtension(SakhrType.LIST, "حجم", emptyList(), SakhrType.NUMBER)
        registerExtension(SakhrType.LIST, "أضف", listOf(SakhrType.UNKNOWN), SakhrType.VOID)
        registerExtension(SakhrType.LIST, "أزل", listOf(SakhrType.UNKNOWN), SakhrType.VOID)
        registerExtension(SakhrType.LIST, "أدخل", listOf(SakhrType.NUMBER, SakhrType.UNKNOWN), SakhrType.VOID)
        registerExtension(SakhrType.LIST, "فهرس", listOf(SakhrType.UNKNOWN), SakhrType.NUMBER)
        registerExtension(SakhrType.LIST, "استبدل", listOf(SakhrType.NUMBER, SakhrType.UNKNOWN), SakhrType.VOID)
        // Element types are erased, so 'خذ' returns UNKNOWN (assignable to anything)
        registerExtension(SakhrType.LIST, "خذ", listOf(SakhrType.NUMBER), SakhrType.UNKNOWN)
    }

    private fun registerBuiltIn(name: String, params: List<SakhrType>, returnType: SakhrType) {
        val sig = FunctionSignature(
            name,
            params.toMutableList(),
            returnType,
            FunctionKind.FUNCTION,
            isParamRequired = List(params.size) { true }
        )
        scopes[0].functions.getOrPut(name) { mutableListOf() }.add(sig)
    }

    private fun registerExtension(
        receiverType: SakhrType,
        name: String,
        params: List<SakhrType>,
        returnType: SakhrType
    ) {
        val sig = FunctionSignature(
            name,
            params.toMutableList(),
            returnType,
            FunctionKind.EXTENSION,
            receiverType,
            isParamRequired = List(params.size) { true }
        )
        val key = "${receiverType.lexeme}::${name}"
        scopes[0].functions.getOrPut(key) { mutableListOf() }.add(sig)
    }

    fun check(statements: List<Stmt>) {
        collectSignatures(statements)

        for (stmt in statements) {
            checkStmt(stmt)
        }
    }

    private fun collectSignatures(statements: List<Stmt>) {
        for (stmt in statements) {
            if (stmt is Stmt.Function) {
                val kind =
                    if (stmt.receiverType != null) FunctionKind.EXTENSION else FunctionKind.FUNCTION
                val receiverType = stmt.receiverType?.let { SakhrType.fromLexeme(it.lexeme) }
                
                val params = stmt.params.map { p ->
                    if (p.type == null) {
                        if (stmt.name.lexeme == "المطلع") {
                            SakhrType.LIST
                        } else if (p.defaultValue != null) {
                            // Try to infer type from default value
                            inferType(p.defaultValue)
                        } else {
                            SakhrType.UNKNOWN
                        }
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
                    receiverType,
                    paramNames = stmt.params.map { it.name.lexeme },
                    isParamRequired = stmt.params.map { it.defaultValue == null }
                )
                val key =
                    if (kind == FunctionKind.EXTENSION) "${receiverType?.lexeme}::${stmt.name.lexeme}" else stmt.name.lexeme
                
                val scope = scopes.last()
                val sigs = scope.functions.getOrPut(key) { mutableListOf() }
                if (sigs.any { it.params == sig.params && it.returnType == sig.returnType }) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "تم تعريف الدالة '${stmt.name.lexeme}' بنفس المواصفات مسبقاً في هذا النطاق.",
                            stmt.name.location
                        )
                    )
                } else {
                    sigs.add(sig)
                }
            } else if (stmt is Stmt.Struct) {
                val fields = mutableMapOf<String, SakhrType>()
                for (field in stmt.fields) {
                    val type = field.type?.let { SakhrType.fromLexeme(it.lexeme) }
                        ?: field.initializer?.let { 
                            SakhrType.UNKNOWN
                        } ?: SakhrType.UNKNOWN
                    fields[field.name.lexeme] = type
                }
                val info = StructInfo(stmt.name.lexeme, fields)
                val scope = scopes.last()
                if (scope.structs.containsKey(stmt.name.lexeme)) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "تم تعريف البنية '${stmt.name.lexeme}' مسبقاً في هذا النطاق.",
                            stmt.name.location
                        )
                    )
                } else {
                    scope.structs[stmt.name.lexeme] = info
                }
            }
        }
    }

    private fun checkStmt(stmt: Stmt) {
        when (stmt) {
            is Stmt.Block -> {
                beginScope()
                collectSignatures(stmt.statements)
                stmt.statements.forEach { checkStmt(it) }
                endScope()
            }

            is Stmt.Expression -> {
                checkExpr(stmt.expression)
            }

            is Stmt.Function -> {
                val kind = if (stmt.receiverType != null) FunctionKind.EXTENSION else FunctionKind.FUNCTION
                val receiverType = stmt.receiverType?.let { SakhrType.fromLexeme(it.lexeme) }
                
                // Restriction for 'المطلع'
                if (stmt.name.lexeme == "المطلع") {
                    for (param in stmt.params) {
                        if (param.defaultValue != null) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "لا يمكن تحديد قيم افتراضية لوسائط دالة 'المطلع'.",
                                    param.name.location
                                )
                            )
                        }
                    }
                }

                val initialParams = stmt.params.map { p ->
                    val type = if (p.type == null) {
                        if (stmt.name.lexeme == "المطلع") {
                            SakhrType.LIST
                        } else if (p.defaultValue != null) {
                            val defaultType = checkExpr(p.defaultValue)
                            defaultType
                        } else {
                            SakhrType.UNKNOWN
                        }
                    } else {
                        val declaredType = SakhrType.fromLexeme(p.type.lexeme)
                        if (p.defaultValue != null) {
                            val defaultType = checkExpr(p.defaultValue)
                            if (!isAssignable(declaredType, defaultType)) {
                                diagnostics.report(
                                    SakhrError.TypeError(
                                        "نوع القيمة الافتراضية '${defaultType.lexeme}' لا يتوافق مع نوع الوسيط '${declaredType.lexeme}'.",
                                        getExprLocation(p.defaultValue)
                                    )
                                )
                            }
                        }
                        declaredType
                    }
                    type
                }
                val returnType = stmt.returnType?.let { SakhrType.fromLexeme(it.lexeme) } ?: SakhrType.VOID

                val key = if (stmt.receiverType != null) {
                    "${SakhrType.fromLexeme(stmt.receiverType.lexeme).lexeme}::${stmt.name.lexeme}"
                } else {
                    stmt.name.lexeme
                }
                
                // Use the signature collected in the first pass
                val scope = scopes.last()
                val sigs = scope.functions[key] ?: mutableListOf()
                val sig = sigs.find { it.params == initialParams && it.returnType == returnType }
                    ?: FunctionSignature(
                        stmt.name.lexeme, 
                        initialParams.toMutableList(), 
                        returnType, 
                        kind, 
                        receiverType,
                        paramNames = stmt.params.map { it.name.lexeme },
                        isParamRequired = stmt.params.map { it.defaultValue == null }
                    )

                val enclosingFunction = currentFunction
                currentFunction = sig

                beginScope()
                collectSignatures(stmt.body)
                if (sig.kind == FunctionKind.EXTENSION) {
                    // "السياق" is available
                    scopes.last().variables["السياق"] =
                        VariableInfo(sig.receiverType!!, isConstant = true, isDefined = true)
                }

                for (i in stmt.params.indices) {
                    val param = stmt.params[i]
                    // Use sig.params[i]: a call site processed earlier may have
                    // inferred a concrete type for an untyped parameter.
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
                            "شرط 'إن كان' يجب أن يكون من نوع 'منطقي'.",
                            getExprLocation(stmt.condition)
                        )
                    )
                }
                checkStmt(stmt.thenBranch)
                stmt.elseBranch?.let { checkStmt(it) }
            }

            is Stmt.While -> {
                val condType = checkExpr(stmt.condition)
                if (condType != SakhrType.BOOLEAN && condType != SakhrType.UNKNOWN) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "شرط 'ما دام' يجب أن يكون من نوع 'منطقي'.",
                            getExprLocation(stmt.condition)
                        )
                    )
                }
                loopDepth++
                checkStmt(stmt.body)
                loopDepth--
            }

            is Stmt.ForEach -> {
                val iterableType = checkExpr(stmt.iterable)
                if (iterableType != SakhrType.LIST && iterableType != SakhrType.UNKNOWN) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "لا يمكن استخدام 'لكل' إلا مع قائمة، لكن النوع المعطى هو '${iterableType.lexeme}'.",
                            getExprLocation(stmt.iterable)
                        )
                    )
                }
                beginScope()
                stmt.indexVar?.let {
                    declare(it, SakhrType.NUMBER, isConstant = true)
                    define(it)
                }
                // Element types are erased in lists, so the element is UNKNOWN
                declare(stmt.elementVar, SakhrType.UNKNOWN, isConstant = true)
                define(stmt.elementVar)
                loopDepth++
                checkStmt(stmt.body)
                loopDepth--
                endScope()
            }

            is Stmt.Break -> {
                if (loopDepth == 0) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "لا يمكن استخدام 'اكفف' خارج حلقة تكرارية.",
                            stmt.keyword.location
                        )
                    )
                }
            }

            is Stmt.Continue -> {
                if (loopDepth == 0) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "لا يمكن استخدام 'امض' خارج حلقة تكرارية.",
                            stmt.keyword.location
                        )
                    )
                }
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
                            "لا يمكن استخدام 'رد' خارج الدالة.",
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

            is Stmt.Struct -> {
                // Fields are already collected in collectSignatures first pass.
                // We just need to check them for valid types if needed.
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
                    // Check if it refers to a struct or function (used as values)
                    val struct = lookupStruct(expr.name.lexeme)
                    if (struct != null) return SakhrType(struct.name)
                    
                    val functions = lookupFunctions(expr.name.lexeme)
                    if (functions.isNotEmpty()) return SakhrType.UNKNOWN // Function as value
                    
                    val allVariables = scopes.flatMap { it.variables.keys }
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
                        // Tolerate erased/unknown operands (e.g. list elements)
                        if (leftType == SakhrType.UNKNOWN || rightType == SakhrType.UNKNOWN) return SakhrType.UNKNOWN
                        diagnostics.report(
                            SakhrError.TypeError(
                                "العملية '+' غير مدعومة بين النوعين '${leftType.lexeme}' و '${rightType.lexeme}'.",
                                expr.operator.location
                            )
                        )
                        SakhrType.UNKNOWN
                    }

                    TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> {
                        if (leftType == SakhrType.NUMBER && rightType == SakhrType.NUMBER) return SakhrType.NUMBER
                        if (leftType == SakhrType.UNKNOWN || rightType == SakhrType.UNKNOWN) return SakhrType.NUMBER
                        diagnostics.report(
                            SakhrError.TypeError(
                                "العملية '${expr.operator.lexeme}' تتطلب أرقاماً.",
                                expr.operator.location
                            )
                        )
                        SakhrType.NUMBER
                    }

                    TokenType.GREATER, TokenType.GREATER_EQUALS,
                    TokenType.LESS, TokenType.LESS_EQUALS -> {
                        if (leftType == SakhrType.NUMBER && rightType == SakhrType.NUMBER) return SakhrType.BOOLEAN
                        if (leftType == SakhrType.UNKNOWN || rightType == SakhrType.UNKNOWN) return SakhrType.BOOLEAN
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

            is Expr.Logical -> {
                val leftType = checkExpr(expr.left)
                val rightType = checkExpr(expr.right)
                if (leftType != SakhrType.BOOLEAN && leftType != SakhrType.UNKNOWN ||
                    rightType != SakhrType.BOOLEAN && rightType != SakhrType.UNKNOWN
                ) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "العملية المنطقية '${expr.operator.lexeme}' تتطلب قيماً منطقية.",
                            expr.operator.location
                        )
                    )
                }
                SakhrType.BOOLEAN
            }

            is Expr.Unary -> {
                val rightType = checkExpr(expr.right)
                when (expr.operator.type) {
                    TokenType.MINUS -> {
                        if (rightType != SakhrType.NUMBER && rightType != SakhrType.UNKNOWN) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "العملية '-' تتطلب رقماً.",
                                    expr.operator.location
                                )
                            )
                        }
                        SakhrType.NUMBER
                    }
                    else -> { // NOT
                        if (rightType != SakhrType.BOOLEAN && rightType != SakhrType.UNKNOWN) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "العملية 'ليس' تتطلب قيمة منطقية.",
                                    expr.operator.location
                                )
                            )
                        }
                        SakhrType.BOOLEAN
                    }
                }
            }

            is Expr.ListLiteral -> {
                val elementTypes = expr.elements.map { checkExpr(it) }
                val commonType = if (elementTypes.isEmpty()) SakhrType.UNKNOWN 
                                 else elementTypes.reduce { acc, t -> if (acc == t) acc else SakhrType.UNKNOWN }
                SakhrType("قائمة", if (commonType == SakhrType.UNKNOWN) null else commonType)
            }

            is Expr.Index -> {
                val objType = checkExpr(expr.obj)
                val indexType = checkExpr(expr.index)
                
                if (indexType != SakhrType.NUMBER && indexType != SakhrType.UNKNOWN) {
                    diagnostics.report(SakhrError.TypeError("يجب أن يكون الفهرس رقماً.", expr.bracket.location))
                }
                
                if (objType.lexeme != "قائمة" && objType != SakhrType.UNKNOWN) {
                    diagnostics.report(SakhrError.TypeError("لا يمكن استخدام الفهرسة إلا مع القوائم.", expr.bracket.location))
                }
                
                objType.elementType ?: SakhrType.UNKNOWN
            }

            is Expr.Call -> {
                val namedArgTypes = mutableMapOf<String, SakhrType>()
                val positionalArgTypes = mutableListOf<SakhrType>()
                
                for (argExpr in expr.arguments) {
                    if (argExpr is Expr.Assignment) {
                        val type = checkExpr(argExpr.value)
                        namedArgTypes[argExpr.name.lexeme] = type
                    } else {
                        val type = checkExpr(argExpr)
                        positionalArgTypes.add(type)
                    }
                }

                val calleeType = checkExpr(expr.callee)
                
                // If it's a direct variable reference, it might be a function name (for overloading)
                if (expr.callee is Expr.Variable) {
                    val name = expr.callee.name.lexeme
                    
                    // Check if it's a Struct constructor
                    val struct = lookupStruct(name)
                    if (struct != null) {
                        return validateStructCall(struct, positionalArgTypes, namedArgTypes, expr.paren.location)
                    }

                    val sig = resolveAndCapture(name, positionalArgTypes, namedArgTypes)
                    if (sig != null) return sig.returnType
                    
                    val functions = lookupFunctions(name)
                    if (functions.isNotEmpty()) {
                        val totalArgs = positionalArgTypes.size + namedArgTypes.size
                        diagnostics.report(
                            SakhrError.TypeError(
                                "لا توجد نسخة من الدالة '$name' تطابق هذه الوسائط ($totalArgs وسائط).",
                                expr.paren.location
                            )
                        )
                        return SakhrType.UNKNOWN
                    }
                    
                    // If not a direct function/struct name, it might be a variable holding a struct/function
                }
                
                // Handle Expr.Get (method calls)
                if (expr.callee is Expr.Get) {
                    val objType = checkExpr(expr.callee.obj)
                    val methodName = expr.callee.name.lexeme
                    
                    // Special case for 'خذ' on a List
                    if (objType.lexeme == "قائمة" && methodName == "خذ") {
                        if (positionalArgTypes.size == 1 && namedArgTypes.isEmpty() && (positionalArgTypes[0] == SakhrType.NUMBER || positionalArgTypes[0] == SakhrType.UNKNOWN)) {
                            return objType.elementType ?: SakhrType.UNKNOWN
                        }
                    }

                    val sig = resolveAndCapture("${objType.lexeme}::${methodName}", positionalArgTypes, namedArgTypes)
                    if (sig == null) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "النوع '${objType.lexeme}' لا يحتوي على دالة ممتدة باسم '${methodName}' تطابق هذه الوسائط.",
                                expr.callee.name.location
                            )
                        )
                        return SakhrType.UNKNOWN
                    }
                    return sig.returnType
                }

                // Check if the type itself is a struct (constructor)
                val struct = lookupStruct(calleeType.lexeme)
                if (struct != null) {
                    return validateStructCall(struct, positionalArgTypes, namedArgTypes, expr.paren.location)
                }

                if (calleeType == SakhrType.UNKNOWN) return SakhrType.UNKNOWN

                diagnostics.report(
                    SakhrError.TypeError(
                        "النوع '${calleeType.lexeme}' غير قابل للاستدعاء.",
                        getExprLocation(expr.callee)
                    )
                )
                SakhrType.UNKNOWN
            }

            is Expr.Get -> {
                val objType = checkExpr(expr.obj)
                val propertyName = expr.name.lexeme

                // Handle struct fields
                val struct = lookupStruct(objType.lexeme)
                if (struct != null) {
                    val fieldType = struct.fields[propertyName]
                    if (fieldType != null) return fieldType
                }

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

            is Expr.Set -> {
                val objType = checkExpr(expr.obj)
                val valueType = checkExpr(expr.value)
                val propertyName = expr.name.lexeme

                // Check mutability
                if (expr.obj is Expr.Variable) {
                    val info = lookupVariable(expr.obj.name)
                    if (info != null && info.isConstant) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "لا يمكن تعديل حقل في كائن معرف كـ 'ألزم'.",
                                expr.name.location
                            )
                        )
                    }
                } else if (expr.obj is Expr.Index || (expr.obj is Expr.Call && (expr.obj.callee as? Expr.Get)?.name?.lexeme == "خذ")) {
                     // The user wants structs in lists to be immutable.
                     // If the object being set is coming directly from an index or 'خذ', it's immutable.
                     diagnostics.report(
                         SakhrError.TypeError(
                             "لا يمكن تعديل حقول البنية المستخرجة من قائمة لأنها ثابتة (غير قابلة للتغيير). يُنصح باستخدام الدالة 'استبدل(الفهرس، القيمة_الجديدة)' لتحديث القائمة بدلاً من ذلك.",
                             expr.name.location
                         )
                     )
                }

                val struct = lookupStruct(objType.lexeme)
                if (struct != null) {
                    val fieldType = struct.fields[propertyName]
                    if (fieldType != null) {
                        if (!isAssignable(fieldType, valueType)) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "نوع الحقل '$propertyName' هو '${fieldType.lexeme}'، ولكن تم تعيين قيمة من نوع '${valueType.lexeme}'.",
                                    expr.name.location
                                )
                            )
                        }
                        return valueType
                    }
                }

                diagnostics.report(
                    SakhrError.TypeError(
                        "النوع '${objType.lexeme}' لا يحتوي على خاصية قابلة للتعيين باسم '${propertyName}'.",
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

    private fun validateStructCall(
        struct: StructInfo,
        positionalArgTypes: List<SakhrType>,
        namedArgTypes: Map<String, SakhrType>,
        location: Location
    ): SakhrType {
        // 1. Positional args
        for (i in positionalArgTypes.indices) {
            if (i >= struct.fields.size) {
                diagnostics.report(SakhrError.TypeError("وسائط زائدة لمنشئ البنية '${struct.name}'.", location))
                break
            }
            val fieldName = struct.fields.keys.elementAt(i)
            val fieldType = struct.fields[fieldName]!!
            if (!isAssignable(fieldType, positionalArgTypes[i])) {
                diagnostics.report(SakhrError.TypeError("نوع الحقل '$fieldName' هو '${fieldType.lexeme}'، ولكن تم تمرير '${positionalArgTypes[i].lexeme}'.", location))
            }
        }

        // 2. Named args
        for ((fieldName, type) in namedArgTypes) {
            val fieldType = struct.fields[fieldName]
            if (fieldType == null) {
                diagnostics.report(SakhrError.TypeError("البنية '${struct.name}' لا تحتوي على حقل باسم '$fieldName'.", location))
                continue
            }

            if (fieldType == SakhrType.UNKNOWN && type != SakhrType.UNKNOWN) {
                // Late inference in Type Checker
                struct.fields[fieldName] = type
            } else if (!isAssignable(fieldType, type)) {
                diagnostics.report(SakhrError.TypeError("نوع الحقل '$fieldName' هو '${fieldType.lexeme}'، ولكن تم تمرير '${type.lexeme}'.", location))
            }
        }

        return SakhrType(struct.name)
    }

    private fun resolveAndCapture(
        name: String,
        positional: List<SakhrType>,
        named: Map<String, SakhrType>
    ): FunctionSignature? {
        val sigs = lookupFunctions(name)
        val sig = sigs.find { sig ->
            val totalArgs = positional.size + named.size
            val minRequired = sig.isParamRequired.count { it }
            if (totalArgs < minRequired || totalArgs > sig.params.size) return@find false
            
            val mappedTypes = arrayOfNulls<SakhrType>(sig.params.size)
            val satisfied = BooleanArray(sig.params.size)
            
            // 1. Positional
            for (i in positional.indices) {
                mappedTypes[i] = positional[i]
                satisfied[i] = true
            }
            
            // 2. Named
            for ((argName, type) in named) {
                val index = sig.paramNames.indexOf(argName)
                if (index == -1) return@find false // Unknown parameter name
                if (satisfied[index]) return@find false // Duplicate (positional and named)
                mappedTypes[index] = type
                satisfied[index] = true
            }
            
            // 3. Check types and required params
            for (i in sig.params.indices) {
                val providedType = mappedTypes[i]
                if (providedType != null) {
                    if (!isAssignable(sig.params[i], providedType)) return@find false
                } else if (i < sig.isParamRequired.size && sig.isParamRequired[i]) {
                    // Required parameter not provided
                    return@find false
                }
            }
            true
        }
        
        if (sig != null) {
            val mappedTypes = arrayOfNulls<SakhrType>(sig.params.size)
            for (i in positional.indices) mappedTypes[i] = positional[i]
            for ((argName, type) in named) {
                val index = sig.paramNames.indexOf(argName)
                if (index != -1) mappedTypes[index] = type
            }

            var modified = false
            val newParams = sig.params.toMutableList()
            for (i in sig.params.indices) {
                val providedType = mappedTypes[i]
                if (providedType != null && sig.params[i] == SakhrType.UNKNOWN && providedType != SakhrType.UNKNOWN) {
                    newParams[i] = providedType
                    modified = true
                }
            }
            if (modified) {
                return sig.copy(params = newParams)
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
            val info = scopes[i].variables[name.lexeme]
            if (info != null) return info
        }
        return null
    }

    private fun lookupFunctions(name: String): List<FunctionSignature> {
        val allSigs = mutableListOf<FunctionSignature>()
        for (i in scopes.size - 1 downTo 0) {
            val sigs = scopes[i].functions[name]
            if (sigs != null) allSigs.addAll(sigs)
        }
        return allSigs
    }

    private fun lookupStruct(name: String): StructInfo? {
        for (i in scopes.size - 1 downTo 0) {
            val info = scopes[i].structs[name]
            if (info != null) return info
        }
        return null
    }

    private fun declare(name: Token, type: SakhrType, isConstant: Boolean) {
        if (scopes.isEmpty()) return
        val scope = scopes.last()
        if (scope.variables.containsKey(name.lexeme)) {
            diagnostics.report(
                SakhrError.TypeError(
                    "تم تعريف الاسم '${name.lexeme}' مسبقاً في هذا النطاق.",
                    name.location
                )
            )
        }
        scope.variables[name.lexeme] = VariableInfo(type, isConstant, false)
    }

    private fun define(name: Token) {
        if (scopes.isEmpty()) return
        val scope = scopes.last()
        val info = scope.variables[name.lexeme]
        if (info != null) {
            scope.variables[name.lexeme] = info.copy(isDefined = true)
        }
    }

    private fun isAssignable(target: SakhrType, source: SakhrType): Boolean {
        if (target == SakhrType.UNKNOWN || source == SakhrType.UNKNOWN) return true
        if (target.lexeme != source.lexeme) return false
        if (target.lexeme == "قائمة") {
            if (target.elementType == null || source.elementType == null) return true
            return isAssignable(target.elementType, source.elementType)
        }
        return true
    }

    private fun beginScope() {
        scopes.add(Scope())
    }

    private fun endScope() {
        scopes.removeAt(scopes.size - 1)
    }

    private fun inferType(expr: Expr): SakhrType {
        return try {
            checkExpr(expr)
        } catch (_: Exception) {
            SakhrType.UNKNOWN
        }
    }

    private fun getExprLocation(expr: Expr): Location {
        return when (expr) {
            is Expr.Variable -> expr.name.location
            is Expr.Binary -> expr.operator.location
            is Expr.Logical -> expr.operator.location
            is Expr.Unary -> expr.operator.location
            is Expr.ListLiteral -> expr.bracket.location
            is Expr.Index -> expr.bracket.location
            is Expr.Call -> expr.paren.location
            is Expr.Get -> expr.name.location
            is Expr.Assignment -> expr.name.location
            is Expr.Context -> expr.keyword.location
            is Expr.Grouping -> getExprLocation(expr.expression)
            is Expr.Literal -> Location(0, 0)
            is Expr.Set -> expr.name.location
        }
    }
}
