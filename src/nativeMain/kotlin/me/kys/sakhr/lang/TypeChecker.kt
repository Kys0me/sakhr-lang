package me.kys.sakhr.lang

class TypeChecker(
    private val diagnostics: DiagnosticEngine,
    private val moduleResolver: ModuleResolver? = null
) {
    private val scopes = mutableListOf<Scope>()
    private val checkedModules = mutableMapOf<String, Scope>()
    private var currentFunction: FunctionSignature? = null
    private var loopDepth = 0

    class Scope {
        val variables = mutableMapOf<String, VariableInfo>()
        val functions = mutableMapOf<String, MutableList<FunctionSignature>>()
        val structs = mutableMapOf<String, StructInfo>()
        val enums = mutableMapOf<String, EnumInfo>()
        
        fun copyPublicSymbols(): Scope {
            val newScope = Scope()
            newScope.variables.putAll(variables) // Assuming all are public for now
            newScope.functions.putAll(functions)
            newScope.structs.putAll(structs)
            newScope.enums.putAll(enums)
            return newScope
        }
    }

    enum class FunctionKind { FUNCTION, EXTENSION }
    data class VariableInfo(val type: SakhrType, val isConstant: Boolean, val isDefined: Boolean)
    data class StructInfo(val name: String, val fields: MutableMap<String, SakhrType>)
    data class EnumInfo(val name: String, val members: Set<String>)
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
        beginScope() // "Super-global" scope for all built-ins
        
        for (module in BuiltIns.modules.values) {
            for (func in module.functions) {
                registerBuiltIn(func.name, func.params, func.returnType)
            }
            for (ext in module.extensions) {
                registerExtension(ext.receiverType, ext.name, ext.params, ext.returnType)
            }
        }
    }

    private fun importSymbols(from: Scope, location: Location) {
        val to = scopes.last()
        
        // Import variables
        for ((name, info) in from.variables) {
            if (to.variables.containsKey(name)) {
                diagnostics.report(
                    SakhrError.TypeError(
                        "تضارب في الأسماء: المتغير '$name' معرف بالفعل في هذا النطاق أو مستجلب من وحدة أخرى.",
                        location
                    )
                )
            } else {
                to.variables[name] = info
            }
        }
        
        // Import functions (handling overloads)
        for ((name, sigs) in from.functions) {
            val toSigs = to.functions.getOrPut(name) { mutableListOf() }
            for (sig in sigs) {
                if (toSigs.any { it.params == sig.params && it.kind == sig.kind && it.receiverType == sig.receiverType }) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "تضارب في الأسماء: الدالة '$name' بنفس التوقيع مستجلابة بالفعل.",
                            location
                        )
                    )
                } else {
                    toSigs.add(sig)
                }
            }
        }
        
        // Import structs
        for ((name, info) in from.structs) {
            if (to.structs.containsKey(name)) {
                diagnostics.report(
                    SakhrError.TypeError(
                        "تضارب في الأسماء: البنية '$name' معرفة بالفعل.",
                        location
                    )
                )
            } else {
                to.structs[name] = info
            }
        }
        
        // Import enums
        for ((name, info) in from.enums) {
            if (to.enums.containsKey(name)) {
                diagnostics.report(
                    SakhrError.TypeError(
                        "تضارب في الأسماء: التعداد '$name' معرف بالفعل.",
                        location
                    )
                )
            } else {
                to.enums[name] = info
            }
        }
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

    fun check(module: Module) {
        if (checkedModules.containsKey(module.path)) return
        
        // Each module gets its own global scope which inherits from the super-global scope
        val moduleScope = Scope()
        
        scopes.add(moduleScope)
        
        collectSignatures(module.statements)
        
        for (stmt in module.statements) {
            checkStmt(stmt)
        }
        
        checkedModules[module.path] = moduleScope
        scopes.removeAt(scopes.size - 1)
    }

    fun check(statements: List<Stmt>) {
        collectSignatures(statements)

        for (stmt in statements) {
            checkStmt(stmt)
        }
    }

    private fun collectSignatures(statements: List<Stmt>) {
        for (stmt in statements) {
            if (stmt is Stmt.Import) {
                if (moduleResolver == null) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "لا يمكن استخدام 'استجلب' في هذا السياق (نظام الوحدات غير مفعل).",
                            stmt.path.first().location
                        )
                    )
                    continue
                }
                
                val importedModule = moduleResolver.resolve(stmt)
                if (importedModule != null) {
                    // Recursively check the imported module
                    check(importedModule)
                    
                    // Import symbols into current scope
                    val importedScope = checkedModules[importedModule.path]
                    if (importedScope != null) {
                        importSymbols(importedScope, stmt.path.first().location)
                    }
                }
            } else if (stmt is Stmt.Function) {
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
                            "توجد دالة أخرى بالاسم '${stmt.name.lexeme}' وبنفس الوسائط والنوع الراجع في هذا النطاق.",
                            stmt.name.location,
                            suggestion = "غيّر اسم الدالة، أو عدّل أنواع وسائطها لتمييزها عن التعريف السابق.",
                            length = stmt.name.lexeme.length
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
                            "توجد بنية أخرى بالاسم '${stmt.name.lexeme}' في هذا النطاق.",
                            stmt.name.location,
                            suggestion = "اختر اسماً مختلفاً للبنية الجديدة، أو احذف التعريف المكرر.",
                            length = stmt.name.lexeme.length
                        )
                    )
                } else {
                    scope.structs[stmt.name.lexeme] = info
                }
            } else if (stmt is Stmt.Enum) {
                val members = stmt.members.map { it.lexeme }.toSet()
                val info = EnumInfo(stmt.name.lexeme, members)
                val scope = scopes.last()
                if (scope.enums.containsKey(stmt.name.lexeme)) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "توجد تسمية أخرى (تعداد أو بنية) بالاسم '${stmt.name.lexeme}' في هذا النطاق.",
                            stmt.name.location,
                            suggestion = "اختر اسماً مختلفاً للتعداد الجديد، أو احذف التعريف المكرر.",
                            length = stmt.name.lexeme.length
                        )
                    )
                } else {
                    scope.enums[stmt.name.lexeme] = info
                }
            }
        }
    }

    private fun checkStmt(stmt: Stmt) {
        when (stmt) {
            is Stmt.Import -> {
                // Imports are handled in collectSignatures, but we need the branch here for exhaustiveness.
            }
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
                                    "دالة 'المطلع' لا تقبل وسائط بقيم افتراضية لأنها تستقبل وسائط التشغيل مباشرة.",
                                    param.name.location,
                                    suggestion = "احذف القيمة الافتراضية من الوسيط '${param.name.lexeme}'.",
                                    length = param.name.lexeme.length
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
                            if (defaultType == SakhrType.NULL_LITERAL) {
                                diagnostics.report(
                                    SakhrError.TypeError(
                                        "تعذر استنتاج نوع الوسيط '${p.name.lexeme}' لأن قيمته الافتراضية 'فارغ'.",
                                        p.name.location,
                                        suggestion = "حدد نوع الوسيط صراحة، مثال: ${p.name.lexeme}: نص؟",
                                        length = p.name.lexeme.length
                                    )
                                )
                            }
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
                                        "القيمة الافتراضية من نوع '${defaultType.lexeme}'، وهذا لا يتوافق مع نوع الوسيط المعلن '${declaredType.lexeme}'.",
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
                            "لا تعيد الدالة '${sig.name}' قيمة في جميع المسارات، رغم أن نوعها الراجع هو '${sig.returnType.lexeme}'.",
                            stmt.name.location,
                            suggestion = "أضف 'رد' بقيمة مناسبة في نهاية الدالة وفي كل فرع من فروع 'إن كان'.",
                            length = sig.name.length
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
                            "شرط 'إن كان' يجب أن يكون قيمة منطقية (صح أو خطأ)، لكن نوعه هنا '${condType.lexeme}'.",
                            getExprLocation(stmt.condition),
                            suggestion = "استخدم مقارنة تنتج قيمة منطقية، مثال: إن كان (العدد > 0)"
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
                            "شرط 'ما دام' يجب أن يكون قيمة منطقية (صح أو خطأ)، لكن نوعه هنا '${condType.lexeme}'.",
                            getExprLocation(stmt.condition),
                            suggestion = "استخدم مقارنة تنتج قيمة منطقية، مثال: ما دام (العدد < 10)"
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
                            "حلقة 'لكل' تكرر على عناصر قائمة فقط، والنوع المعطى هنا '${iterableType.lexeme}'.",
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
                            "الكلمة 'اكفف' توقف الحلقة، ولا يمكن استخدامها خارج حلقة 'ما دام' أو 'لكل'.",
                            stmt.keyword.location,
                            length = stmt.keyword.lexeme.length
                        )
                    )
                }
            }

            is Stmt.Continue -> {
                if (loopDepth == 0) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "الكلمة 'امض' تنتقل إلى الدورة التالية، ولا يمكن استخدامها خارج حلقة 'ما دام' أو 'لكل'.",
                            stmt.keyword.location,
                            length = stmt.keyword.lexeme.length
                        )
                    )
                }
            }

            is Stmt.Match -> {
                val exprType = checkExpr(stmt.expression)
                
                // Restriction: cannot use struct instance for pattern matching
                val struct = lookupStruct(exprType.lexeme)
                if (struct != null) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "لا يمكن استخدام مثيل من البنية '${exprType.lexeme}' مباشرة في جملة 'طابق'.",
                            getExprLocation(stmt.expression),
                            suggestion = "طابق بدلاً من ذلك على أحد حقول البنية، مثال: طابق ${exprType.lexeme}.الحقل"
                        )
                    )
                }

                val coveredPatterns = mutableSetOf<String>()
                for (case in stmt.cases) {
                    val patternType = checkExpr(case.pattern)
                    if (!isAssignable(exprType, patternType)) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "النمط من نوع '${patternType.lexeme}' لا يطابق نوع التعبير الممرر '${exprType.lexeme}'.",
                                getExprLocation(case.pattern)
                            )
                        )
                    }
                    
                    // Track covered patterns for exhaustiveness
                    when (val p = case.pattern) {
                        is Expr.Literal -> coveredPatterns.add(p.value.toString())
                        is Expr.Variable -> coveredPatterns.add(p.name.lexeme)
                        is Expr.Get -> coveredPatterns.add(p.name.lexeme)
                        else -> {}
                    }
                    
                    checkStmt(case.body)
                }
                
                stmt.defaultBranch?.let { checkStmt(it) }

                // Exhaustiveness check
                if (stmt.defaultBranch == null) {
                    if (exprType == SakhrType.BOOLEAN) {
                        val hasTrue = coveredPatterns.contains("true") || coveredPatterns.contains("صح")
                        val hasFalse = coveredPatterns.contains("false") || coveredPatterns.contains("خطأ")
                        if (!hasTrue || !hasFalse) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "جملة 'طابق' على قيم منطقية يجب أن تغطي كلتا الحالتين (صح وخطأ) أو تحتوي على 'وإلا'.",
                                    getExprLocation(stmt.expression)
                                )
                            )
                        }
                    } else {
                        val enum = lookupEnum(exprType.lexeme)
                        if (enum != null) {
                            val missing = enum.members.filter { !coveredPatterns.contains(it) }
                            if (missing.isNotEmpty()) {
                                diagnostics.report(
                                    SakhrError.TypeError(
                                        "جملة 'طابق' على التعداد '${enum.name}' غير مكتملة؛ الحالات التالية غير مغطاة: ${missing.joinToString("، ")}.",
                                        getExprLocation(stmt.expression),
                                        suggestion = "أضف حالات لهذه القيم، أو أضف فرع 'وإلا' للتعامل مع الحالات المتبقية."
                                    )
                                )
                            }
                        }
                    }
                }
            }

            is Stmt.Let -> {
                val explicitType = stmt.type?.let { SakhrType.fromLexeme(it.lexeme) }
                val initType = stmt.initializer?.let { checkExpr(it) } ?: SakhrType.VOID

                if (stmt.names.size > 1) {
                    // For destructuring, we usually don't have an explicit type for the whole thing.
                    // If we do, it's tricky. For now, we allow it if no explicit type is provided.
                    if (explicitType != null) {
                         diagnostics.report(SakhrError.TypeError("لا يمكن تحديد نوع صريح عند تفكيك قيمة إلى عدة متغيرات.", stmt.names[0].location, suggestion = "احذف النوع الصريح؛ تُستنتج أنواع المتغيرات تلقائياً عند التفكيك."))
                    }
                    
                    for (name in stmt.names) {
                        declare(name, SakhrType.UNKNOWN, isConstant = false)
                        define(name)
                    }
                } else {
                    val nameToken = stmt.names[0]
                    if (initType == SakhrType.NULL_LITERAL && explicitType == null) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "تعذر استنتاج نوع المتغير '${nameToken.lexeme}' لأن قيمته الابتدائية 'فارغ'.",
                                nameToken.location,
                                suggestion = "حدد نوع المتغير صراحة، مثال: ليكن ${nameToken.lexeme}: نص؟ = فارغ",
                                length = nameToken.lexeme.length
                            )
                        )
                    }

                    val finalType = if (explicitType != null) {
                        if (stmt.initializer != null && !isAssignable(explicitType, initType)) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "لا يمكن إسناد قيمة من نوع '${initType.lexeme}' إلى المتغير '${nameToken.lexeme}' المعرف بنوع '${explicitType.lexeme}'.",
                                    nameToken.location,
                                    length = nameToken.lexeme.length
                                )
                            )
                        }
                        explicitType
                    } else {
                        initType
                    }
                    
                    declare(nameToken, finalType, isConstant = false)
                    define(nameToken)
                }
            }

            is Stmt.Const -> {
                val explicitType = stmt.type?.let { SakhrType.fromLexeme(it.lexeme) }
                val initType = checkExpr(stmt.initializer)
                
                if (stmt.names.size > 1) {
                    if (explicitType != null) {
                        diagnostics.report(SakhrError.TypeError("لا يمكن تحديد نوع صريح عند تفكيك قيمة إلى عدة ثوابت.", stmt.names[0].location, suggestion = "احذف النوع الصريح؛ تُستنتج أنواع الثوابت تلقائياً عند التفكيك."))
                    }
                    for (name in stmt.names) {
                        declare(name, SakhrType.UNKNOWN, isConstant = true)
                        define(name)
                    }
                } else {
                    val nameToken = stmt.names[0]
                    if (initType == SakhrType.NULL_LITERAL && explicitType == null) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "تعذر استنتاج نوع الثابت '${nameToken.lexeme}' لأن قيمته الابتدائية 'فارغ'.",
                                nameToken.location,
                                suggestion = "حدد نوع الثابت صراحة، مثال: ألزم ${nameToken.lexeme}: نص؟ = فارغ",
                                length = nameToken.lexeme.length
                            )
                        )
                    }

                    val finalType = if (explicitType != null) {
                        if (!isAssignable(explicitType, initType)) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "لا يمكن إسناد قيمة من نوع '${initType.lexeme}' إلى الثابت '${nameToken.lexeme}' المعرف بنوع '${explicitType.lexeme}'.",
                                    nameToken.location,
                                    length = nameToken.lexeme.length
                                )
                            )
                        }
                        explicitType
                    } else {
                        initType
                    }
                    
                    declare(nameToken, finalType, isConstant = true)
                    define(nameToken)
                }
            }

            is Stmt.Return -> {
                if (currentFunction == null) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "الكلمة 'رد' تعيد قيمة من دالة، ولا يمكن استخدامها خارج دالة.",
                            stmt.keyword.location,
                            length = stmt.keyword.lexeme.length
                        )
                    )
                }
                val valueType = stmt.value?.let { checkExpr(it) } ?: SakhrType.VOID
                if (currentFunction != null && !isAssignable(currentFunction!!.returnType, valueType)) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "قيمة 'رد' من نوع '${valueType.lexeme}'، وهذا لا يطابق النوع الراجع للدالة '${currentFunction!!.returnType.lexeme}'.",
                            stmt.keyword.location,
                            length = stmt.keyword.lexeme.length
                        )
                    )
                }
            }
            
            is Stmt.Raise -> {
                checkExpr(stmt.message)
            }

            is Stmt.Struct -> {
                // Fields are already collected in collectSignatures first pass.
                // We just need to check them for valid types if needed.
            }

            is Stmt.Enum -> {
                // Members are already collected in collectSignatures first pass.
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
                    SakhrUnit -> SakhrType.VOID
                    null -> SakhrType.NULL_LITERAL
                    else -> SakhrType.UNKNOWN
                }
            }

            is Expr.Variable -> {
                val info = lookupVariable(expr.name)
                if (info == null) {
                    // Check if it refers to a struct, enum or function (used as values)
                    val struct = lookupStruct(expr.name.lexeme)
                    if (struct != null) return SakhrType(struct.name)

                    val enum = lookupEnum(expr.name.lexeme)
                    if (enum != null) return SakhrType(enum.name)
                    
                    val functions = lookupFunctions(expr.name.lexeme)
                    if (functions.isNotEmpty()) return SakhrType.UNKNOWN // Function as value
                    
                    val allVariables = scopes.flatMap { it.variables.keys }
                    val suggestion = DiagnosticEngine.findClosest(expr.name.lexeme, allVariables)
                    diagnostics.report(
                        SakhrError.TypeError(
                            "المتغير '${expr.name.lexeme}' غير معرف في هذا النطاق.",
                            expr.name.location,
                            suggestion = suggestion?.let { "هل قصدت '$it'؟" }
                                ?: "عرّف المتغير قبل استخدامه، مثال: ليكن ${expr.name.lexeme} = ...",
                            length = expr.name.lexeme.length
                        )
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
                    val allVariables = scopes.flatMap { it.variables.keys }
                    val suggestion = DiagnosticEngine.findClosest(expr.name.lexeme, allVariables)
                    diagnostics.report(
                        SakhrError.TypeError(
                            "لا يمكن التعيين للمتغير '${expr.name.lexeme}' لأنه غير معرف.",
                            expr.name.location,
                            suggestion = suggestion?.let { "هل قصدت '$it'؟" }
                                ?: "عرّف المتغير أولاً بـ'ليكن' قبل إسناد قيمة إليه.",
                            length = expr.name.lexeme.length
                        )
                    )
                } else {
                    if (info.isConstant) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "لا يمكن تغيير قيمة '${expr.name.lexeme}' لأنه ثابت معرف بـ'ألزم'.",
                                expr.name.location,
                                suggestion = "إذا كنت تحتاج إلى تغيير قيمته، عرّفه بـ'ليكن' بدلاً من 'ألزم'.",
                                length = expr.name.lexeme.length
                            )
                        )
                    }
                    if (!isAssignable(info.type, valueType)) {
                        diagnostics.report(
                            SakhrError.TypeError(
                                "لا يمكن إسناد قيمة من نوع '${valueType.lexeme}' إلى المتغير '${expr.name.lexeme}' المعرف بنوع '${info.type.lexeme}'.",
                                expr.name.location,
                                length = expr.name.lexeme.length
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
                                "لا يمكن تطبيق العملية '+' بين قيمة من نوع '${leftType.lexeme}' وأخرى من نوع '${rightType.lexeme}'.",
                                expr.operator.location,
                                suggestion = "العملية '+' تجمع الأرقام أو تدمج النصوص؛ تأكد من توافق نوعي الطرفين.",
                                length = expr.operator.lexeme.length
                            )
                        )
                        SakhrType.UNKNOWN
                    }

                    TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> {
                        if (leftType == SakhrType.NUMBER && rightType == SakhrType.NUMBER) return SakhrType.NUMBER
                        if (leftType == SakhrType.UNKNOWN || rightType == SakhrType.UNKNOWN) return SakhrType.NUMBER
                        diagnostics.report(
                            SakhrError.TypeError(
                                "العملية '${expr.operator.lexeme}' تعمل مع الأرقام فقط، وأحد الطرفين من نوع '${if (leftType != SakhrType.NUMBER) leftType.lexeme else rightType.lexeme}'.",
                                expr.operator.location,
                                length = expr.operator.lexeme.length
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
                                "عمليات المقارنة (أكبر/أصغر) تعمل مع الأرقام فقط، والطرفان هنا من نوع '${leftType.lexeme}' و'${rightType.lexeme}'.",
                                expr.operator.location,
                                length = expr.operator.lexeme.length
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
                            "العملية المنطقية '${expr.operator.lexeme}' تعمل مع القيم المنطقية (صح أو خطأ) فقط.",
                            expr.operator.location,
                            length = expr.operator.lexeme.length
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
                                    "علامة السالب '-' تعمل مع الأرقام فقط، والقيمة هنا من نوع '${rightType.lexeme}'.",
                                    expr.operator.location,
                                    length = expr.operator.lexeme.length
                                )
                            )
                        }
                        SakhrType.NUMBER
                    }
                    else -> { // NOT
                        if (rightType != SakhrType.BOOLEAN && rightType != SakhrType.UNKNOWN) {
                            diagnostics.report(
                                SakhrError.TypeError(
                                    "النفي 'ليس' يعمل مع القيم المنطقية (صح أو خطأ) فقط، والقيمة هنا من نوع '${rightType.lexeme}'.",
                                    expr.operator.location,
                                    length = expr.operator.lexeme.length
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
                    diagnostics.report(SakhrError.TypeError("فهرس القائمة يجب أن يكون رقماً، والنوع المعطى هنا '${indexType.lexeme}'.", expr.bracket.location))
                }
                
                if (objType.lexeme != "قائمة" && objType != SakhrType.UNKNOWN) {
                    diagnostics.report(SakhrError.TypeError("الفهرسة بالأقواس [] تُستخدم مع القوائم فقط، والنوع هنا '${objType.lexeme}'.", expr.bracket.location))
                }
                
                objType.elementType ?: SakhrType.UNKNOWN
            }

            is Expr.Call -> {
                val namedArgTypes = mutableMapOf<String, SakhrType>()
                val namedArgLocations = mutableMapOf<String, Location>()
                val positionalArgTypes = mutableListOf<SakhrType>()
                
                for (argExpr in expr.arguments) {
                    if (argExpr is Expr.Assignment) {
                        val type = checkExpr(argExpr.value)
                        namedArgTypes[argExpr.name.lexeme] = type
                        namedArgLocations[argExpr.name.lexeme] = argExpr.name.location
                    } else {
                        val type = checkExpr(argExpr)
                        positionalArgTypes.add(type)
                    }
                }

                // If it's a direct variable reference, it might be a function name (for overloading)
                if (expr.callee is Expr.Variable) {
                    val name = expr.callee.name.lexeme
                    
                    // Check if it's a Struct constructor
                    val struct = lookupStruct(name)
                    if (struct != null) {
                        return validateStructCall(struct, positionalArgTypes, namedArgTypes, expr.paren.location, namedArgLocations)
                    }

                    val sig = resolveAndCapture(name, positionalArgTypes, namedArgTypes)
                    if (sig != null) return sig.returnType
                    
                    val functions = lookupFunctions(name)
                    if (functions.isNotEmpty()) {
                        val totalArgs = positionalArgTypes.size + namedArgTypes.size
                        diagnostics.report(
                            SakhrError.TypeError(
                                "لا توجد نسخة من الدالة '$name' تقبل هذه الوسائط ($totalArgs وسائط).",
                                expr.paren.location,
                                suggestion = "تأكد من عدد الوسائط وأنواعها، ومن مطابقتها لأحد تعريفات الدالة."
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
                                "لا توجد دالة ممتدة بالاسم '${methodName}' للنوع '${objType.lexeme}' تقبل هذه الوسائط.",
                                expr.callee.name.location,
                                length = methodName.length
                            )
                        )
                        return SakhrType.UNKNOWN
                    }
                    return sig.returnType
                }

                // Fallback: only reached when the callee is neither a Variable nor a
                // Get handled above (e.g. a variable holding a struct value used as a
                // constructor). Checking the callee's type here avoids re-checking
                // Variable/Get callees, which previously produced duplicate diagnostics.
                val calleeType = checkExpr(expr.callee)

                // Check if the type itself is a struct (constructor)
                val struct = lookupStruct(calleeType.lexeme)
                if (struct != null) {
                    return validateStructCall(struct, positionalArgTypes, namedArgTypes, expr.paren.location, namedArgLocations)
                }

                if (calleeType == SakhrType.UNKNOWN) return SakhrType.UNKNOWN

                diagnostics.report(
                    SakhrError.TypeError(
                        "لا يمكن استدعاء قيمة من نوع '${calleeType.lexeme}'؛ الاستدعاء ممكن للدوال ومنشئات البنى فقط.",
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

                // Handle enum members
                val enum = lookupEnum(objType.lexeme)
                if (enum != null) {
                    if (enum.members.contains(propertyName)) {
                        return SakhrType(enum.name)
                    }
                }

                // Check for extension methods ('حجم' included: the interpreter treats
                // bare 'list.حجم' as a bound extension reference, not a number)
                if (lookupFunctions("${objType.lexeme}::$propertyName").isNotEmpty()) {
                    return SakhrType.UNKNOWN // Function/Method reference
                }

                val fieldNames = struct?.fields?.keys ?: emptySet()
                val suggestion = DiagnosticEngine.findClosest(propertyName, fieldNames)
                diagnostics.report(
                    SakhrError.TypeError(
                        "النوع '${objType.lexeme}' لا يحتوي على خاصية أو دالة ممتدة باسم '${propertyName}'.",
                        expr.name.location,
                        suggestion = suggestion?.let { "هل قصدت '$it'؟" },
                        length = propertyName.length
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
                                "لا يمكن تعديل حقل في الكائن '${expr.obj.name.lexeme}' لأنه ثابت معرف بـ'ألزم'.",
                                expr.name.location,
                                suggestion = "عرّف الكائن بـ'ليكن' إذا كنت تحتاج إلى تعديل حقوله.",
                                length = expr.name.lexeme.length
                            )
                        )
                    }
                } else if (expr.obj is Expr.Index || (expr.obj is Expr.Call && (expr.obj.callee as? Expr.Get)?.name?.lexeme == "خذ")) {
                     // The user wants structs in lists to be immutable.
                     // If the object being set is coming directly from an index or 'خذ', it's immutable.
                     diagnostics.report(
                         SakhrError.TypeError(
                             "البنى المستخرجة من قائمة ثابتة ولا يمكن تعديل حقولها مباشرة.",
                             expr.name.location,
                             suggestion = "استخدم الدالة 'استبدل' لتحديث القائمة، مثال: القائمة.استبدل(الفهرس، القيمة_الجديدة)",
                             length = expr.name.lexeme.length
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
                                    "الحقل '$propertyName' من نوع '${fieldType.lexeme}'، ولا يمكن إسناد قيمة من نوع '${valueType.lexeme}' إليه.",
                                    expr.name.location,
                                    length = propertyName.length
                                )
                            )
                        }
                        return valueType
                    }
                }

                val fieldNames = struct?.fields?.keys ?: emptySet()
                val suggestion = DiagnosticEngine.findClosest(propertyName, fieldNames)
                diagnostics.report(
                    SakhrError.TypeError(
                        "النوع '${objType.lexeme}' لا يحتوي على حقل قابل للتعيين باسم '${propertyName}'.",
                        expr.name.location,
                        suggestion = suggestion?.let { "هل قصدت '$it'؟" },
                        length = propertyName.length
                    )
                )
                SakhrType.UNKNOWN
            }

            is Expr.Context -> {
                if (currentFunction?.kind != FunctionKind.EXTENSION) {
                    diagnostics.report(
                        SakhrError.TypeError(
                            "الكلمة 'السياق' تشير إلى القيمة المستقبلة، ولا تتوفر إلا داخل الدوال الممتدة.",
                            expr.keyword.location,
                            length = expr.keyword.lexeme.length
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
        location: Location,
        namedArgLocations: Map<String, Location> = emptyMap()
    ): SakhrType {
        // 1. Positional args
        for (i in positionalArgTypes.indices) {
            if (i >= struct.fields.size) {
                diagnostics.report(SakhrError.TypeError("عدد الوسائط الممررة لمنشئ البنية '${struct.name}' أكبر من عدد حقولها (${struct.fields.size}).", location, suggestion = "احذف الوسائط الزائدة حتى تطابق حقول البنية."))
                break
            }
            val fieldName = struct.fields.keys.elementAt(i)
            val fieldType = struct.fields[fieldName]!!
            if (!isAssignable(fieldType, positionalArgTypes[i])) {
                diagnostics.report(SakhrError.TypeError("الحقل '$fieldName' من نوع '${fieldType.lexeme}'، ولا يمكن تمرير قيمة من نوع '${positionalArgTypes[i].lexeme}' له.", location))
            }
        }

        // 2. Named args
        for ((fieldName, type) in namedArgTypes) {
            val argLoc = namedArgLocations[fieldName] ?: location
            val fieldType = struct.fields[fieldName]
            if (fieldType == null) {
                val suggestion = DiagnosticEngine.findClosest(fieldName, struct.fields.keys)
                diagnostics.report(SakhrError.TypeError("البنية '${struct.name}' لا تملك حقلاً باسم '$fieldName'.", argLoc, suggestion = suggestion?.let { "هل قصدت '$it'؟" }, length = fieldName.length))
                continue
            }

            if (fieldType == SakhrType.UNKNOWN && type != SakhrType.UNKNOWN) {
                // Late inference in Type Checker
                struct.fields[fieldName] = type
            } else if (!isAssignable(fieldType, type)) {
                diagnostics.report(SakhrError.TypeError("الحقل '$fieldName' من نوع '${fieldType.lexeme}'، ولا يمكن تمرير قيمة من نوع '${type.lexeme}' له.", argLoc, length = fieldName.length))
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
            // Raising an error also terminates the current path, so a branch
            // ending in 'بلغ' counts as returning on that path.
            is Stmt.Raise -> true
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

    private fun lookupEnum(name: String): EnumInfo? {
        for (i in scopes.size - 1 downTo 0) {
            val info = scopes[i].enums[name]
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
                    "الاسم '${name.lexeme}' معرف مسبقاً في هذا النطاق.",
                    name.location,
                    suggestion = "اختر اسماً مختلفاً، أو استخدم الاسم الموجود مباشرة دون إعادة تعريفه.",
                    length = name.lexeme.length
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
        // 'فارغ' is only assignable to optional types; letting it satisfy any
        // non-optional type would defeat the optionality ('؟') guarantees.
        if (source == SakhrType.NULL_LITERAL) return target.isOptional
        
        // If target is optional, we allow assignment from its base type
        if (target.isOptional && !source.isOptional && target.lexeme == source.lexeme) {
             if (target.lexeme == "قائمة") {
                 if (target.elementType == null || source.elementType == null) return true
                 return isAssignable(target.elementType, source.elementType)
             }
             return true
        }

        if (target.lexeme != source.lexeme) return false
        if (target.isOptional != source.isOptional && !target.isOptional) return false

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
            is Expr.Literal -> expr.location ?: Location(0, 0)
            is Expr.Set -> expr.name.location
        }
    }
}
