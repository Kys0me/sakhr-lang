package me.kys.sakhr.lang

interface SakhrCallable {
    fun arity(): Int
    fun minArity(): Int = arity()
    fun maxArity(): Int = arity()
    fun call(
        interpreter: Interpreter,
        arguments: List<Any?>,
        namedArguments: Map<String, Any?> = emptyMap(),
        location: Location
    ): Any?
}

interface SakhrExtension : SakhrCallable {
    fun callWithContext(
        interpreter: Interpreter,
        arguments: List<Any?>,
        namedArguments: Map<String, Any?>,
        context: Any?,
        location: Location
    ): Any?
}

object SakhrUnit

data class SakhrResult(val value: Any?, val error: Any?)

class Return(val value: Any?) : RuntimeException()
class SakhrRaiseException(val error: Any?) : RuntimeException()

// Loop control signals carry no state, so they are reused as singletons to
// avoid an allocation (and a backtrace capture) on every break/continue.
class BreakSignal : RuntimeException()
class ContinueSignal : RuntimeException()

class SakhrFunction(
    private val declaration: Stmt.Function,
    private val closure: Environment,
    private val isExtension: Boolean
) : SakhrExtension {
    override fun arity(): Int = declaration.params.size
    override fun minArity(): Int = declaration.params.count { it.defaultValue == null }
    override fun maxArity(): Int = declaration.params.size

    override fun call(
        interpreter: Interpreter,
        arguments: List<Any?>,
        namedArguments: Map<String, Any?>,
        location: Location
    ): Any? =
        callWithContext(interpreter, arguments, namedArguments, null, location)

    override fun callWithContext(
        interpreter: Interpreter,
        arguments: List<Any?>,
        namedArguments: Map<String, Any?>,
        context: Any?,
        location: Location
    ): Any? {
        val environment = Environment(closure)
        if (isExtension && context != null) {
            environment.define("السياق", context, true)
        }

        // Match arguments to parameters
        for ((i, param) in declaration.params.withIndex()) {
            val value = when {
                i < arguments.size -> arguments[i]
                namedArguments.containsKey(param.name.lexeme) -> namedArguments[param.name.lexeme]
                param.defaultValue != null -> interpreter.evaluateInEnvironment(
                    param.defaultValue,
                    environment
                )

                else -> null // Should be unreachable if arity check passes
            }
            environment.define(param.name.lexeme, value, false)
        }

        try {
            interpreter.executeBlock(declaration.body, environment)
        } catch (returnValue: Return) {
            val resultValue = returnValue.value
            return if (declaration.returnType?.lexeme?.endsWith("؟") == true) {
                SakhrResult(resultValue, null)
            } else {
                resultValue
            }
        } catch (raise: SakhrRaiseException) {
            if (declaration.returnType?.lexeme?.endsWith("؟") == true) {
                return SakhrResult(null, raise.error)
            } else {
                throw raise
            }
        }
        val unitResult = SakhrUnit
        return if (declaration.returnType?.lexeme?.endsWith("؟") == true) {
            SakhrResult(unitResult, null)
        } else {
            unitResult
        }
    }
}

class SakhrStruct(
    val declaration: Stmt.Struct,
    val closure: Environment
) : SakhrCallable {
    private val fieldTypes = mutableMapOf<String, String>()

    init {
        for (field in declaration.fields) {
            val typeLexeme = field.type?.lexeme
            if (typeLexeme != null) {
                fieldTypes[field.name.lexeme] = typeLexeme
            }
        }
    }

    override fun arity(): Int = 0 // Variadic-like for named args

    override fun call(
        interpreter: Interpreter,
        arguments: List<Any?>,
        namedArguments: Map<String, Any?>,
        location: Location
    ): Any {
        interpreter.pushStructInitialization(this, location)
        try {
            val instance = SakhrInstance(this)

            // 1. Initial values from declaration (defaults)
            for (field in declaration.fields) {
                instance.fields[field.name.lexeme] = if (field.initializer != null) {
                    interpreter.evaluateInEnvironment(field.initializer, closure)
                } else {
                    null
                }
            }

            // 2. Positional arguments
            for (i in arguments.indices) {
                if (i < declaration.fields.size) {
                    val field = declaration.fields[i]
                    val value = arguments[i]
                    validateAndSetField(instance, field.name, value, interpreter)
                }
            }

            // 3. Named arguments
            for ((name, value) in namedArguments) {
                val field = declaration.fields.find { it.name.lexeme == name }
                    ?: run {
                        val hint = DiagnosticEngine.findClosest(
                            name,
                            declaration.fields.map { it.name.lexeme }
                        )?.let { "هل قصدت الحقل '$it'؟" }
                            ?: "حقول البنية المتاحة: ${declaration.fields.joinToString("، ") { it.name.lexeme }}."
                        throw SakhrError.RuntimeError(
                            "البنية '${declaration.name.lexeme}' لا تملك حقلاً باسم '$name'.",
                            location,
                            hint
                        )
                    }
                validateAndSetField(instance, field.name, value, interpreter)
            }

            return instance
        } finally {
            interpreter.popStructInitialization()
        }
    }

    fun getFieldType(name: String): String? = fieldTypes[name]

    private fun validateAndSetField(
        instance: SakhrInstance,
        fieldName: Token,
        value: Any?,
        interpreter: Interpreter
    ) {
        val name = fieldName.lexeme
        val typeName = interpreter.getSakhrTypeName(value)

        val expectedType = fieldTypes[name]
        if (expectedType == null && value != null) {
            // Late inference
            fieldTypes[name] = typeName
        }

        if (expectedType != null && typeName != expectedType && value != null) {
            throw SakhrError.RuntimeError(
                "لا يمكن إسناد قيمة من نوع '$typeName' إلى الحقل '$name' لأنه معرّف بالنوع '$expectedType'.",
                fieldName.location,
                length = name.length
            )
        }

        instance.fields[name] = value
    }
}

class SakhrInstance(val struct: SakhrStruct) {
    val fields = mutableMapOf<String, Any?>()
    var isMutable = true

    fun stringify(interpreter: Interpreter): String {
        val fieldStr =
            fields.entries.joinToString("، ") { "${it.key}: ${interpreter.stringify(it.value)}" }
        return "${struct.declaration.name.lexeme}($fieldStr)"
    }

    fun asImmutable(): SakhrInstance {
        val new = SakhrInstance(struct)
        new.fields.putAll(this.fields)
        new.isMutable = false
        return new
    }
}

class SakhrEnum(val name: String, val members: Set<String>)

data class SakhrEnumValue(val enum: SakhrEnum, val name: String)

class Interpreter(private val diagnostics: DiagnosticEngine) : Backend {
    val globals = Environment()
    private var environment = globals
    private val structInitializationStack = mutableListOf<SakhrStruct>()

    init {
        for (func in BuiltIns.functions) {
            globals.define(func.name, object : SakhrCallable {
                override fun arity(): Int = func.params.size
                override fun call(
                    interpreter: Interpreter,
                    arguments: List<Any?>,
                    namedArguments: Map<String, Any?>,
                    location: Location
                ): Any? =
                    func.call(interpreter, arguments, namedArguments, location)
            }, true)
        }

        for (ext in BuiltIns.extensions) {
            defineBuiltInExtension(
                ext.receiverType.lexeme,
                ext.name,
                ext.params.size
            ) { interpreter, args, namedArgs, context, location ->
                ext.call(interpreter, args, namedArgs, context, location)
            }
        }
    }

    private fun defineBuiltInExtension(
        typeName: String,
        methodName: String,
        arity: Int,
        call: (Interpreter, List<Any?>, Map<String, Any?>, Any?, Location) -> Any?
    ) {
        globals.define("${typeName}::${methodName}", object : SakhrExtension {
            override fun arity(): Int = arity
            override fun call(
                interpreter: Interpreter,
                arguments: List<Any?>,
                namedArguments: Map<String, Any?>,
                location: Location
            ): Any =
                throw SakhrError.RuntimeError(
                    "الدالة الممتدة '${methodName}' لا تُستدعى مباشرة، بل تُستدعى على قيمة من النوع الذي تمتده.",
                    location,
                    "استخدم الصيغة 'القيمة.${methodName}(...)' لاستدعائها."
                )

            override fun callWithContext(
                interpreter: Interpreter,
                arguments: List<Any?>,
                namedArguments: Map<String, Any?>,
                context: Any?,
                location: Location
            ): Any? = call(interpreter, arguments, namedArguments, context, location)
        }, true)
    }

    fun pushStructInitialization(struct: SakhrStruct, location: Location) {
        if (structInitializationStack.size > 100) {
            throw SakhrError.RuntimeError(
                "تجاوزت تهيئة البنية '${struct.declaration.name.lexeme}' الحد الأقصى للعمق، ويرجّح وجود تكرار لا نهائي.",
                location,
                "تحقق من أن حقول البنية لا تُنشئ نسخة منها أثناء التهيئة."
            )
        }
        structInitializationStack.add(struct)
    }

    fun popStructInitialization() {
        if (structInitializationStack.isNotEmpty()) {
            structInitializationStack.removeAt(structInitializationStack.size - 1)
        }
    }

    override fun execute(statements: List<Stmt>) {
        try {
            for (stmt in statements) {
                execute(stmt)
            }
        } catch (error: SakhrError.RuntimeError) {
            diagnostics.report(error)
        } catch (raise: SakhrRaiseException) {
            diagnostics.report(
                SakhrError.RuntimeError(
                    "أُطلق خطأ لم تتم معالجته: ${stringify(raise.error)}",
                    Location(0, 0)
                )
            )
        }
    }

    private fun execute(stmt: Stmt) {
        when (stmt) {
            is Stmt.Block -> executeBlock(stmt.statements, Environment(environment))
            is Stmt.Expression -> evaluate(stmt.expression)
            is Stmt.Function -> {
                val function = SakhrFunction(stmt, environment, stmt.receiverType != null)
                if (stmt.receiverType != null) {
                    globals.define(
                        "${stmt.receiverType.lexeme}::${stmt.name.lexeme}",
                        function,
                        true
                    )
                } else {
                    environment.define(stmt.name.lexeme, function, true)
                }
            }

            is Stmt.Struct -> {
                val struct = SakhrStruct(stmt, environment)
                environment.define(stmt.name.lexeme, struct, true)
            }

            is Stmt.Enum -> {
                val sakhrEnum = SakhrEnum(stmt.name.lexeme, stmt.members.map { it.lexeme }.toSet())
                environment.define(stmt.name.lexeme, sakhrEnum, true)
            }

            is Stmt.If -> {
                if (isTruthy(evaluate(stmt.condition))) {
                    execute(stmt.thenBranch)
                } else if (stmt.elseBranch != null) {
                    execute(stmt.elseBranch)
                }
            }

            is Stmt.Match -> {
                val value = evaluate(stmt.expression)
                var matched = false
                for (case in stmt.cases) {
                    val pattern = evaluate(case.pattern)
                    if (value == pattern) {
                        execute(case.body)
                        matched = true
                        break
                    }
                }
                if (!matched && stmt.defaultBranch != null) {
                    execute(stmt.defaultBranch)
                }
            }

            is Stmt.While -> {
                while (isTruthy(evaluate(stmt.condition))) {
                    try {
                        execute(stmt.body)
                    } catch (_: BreakSignal) {
                        break
                    } catch (_: ContinueSignal) {
                        continue
                    }
                }
            }

            is Stmt.ForEach -> {
                val iterable = evaluate(stmt.iterable)
                if (iterable !is List<*>) {
                    throw SakhrError.RuntimeError(
                        "لا يمكن التكرار إلا على قائمة، والقيمة المعطاة من نوع '${getSakhrTypeName(iterable)}'.",
                        stmt.elementVar.location,
                        "تأكد من أن ما بعد 'في' قائمة، مثل: لكل عنصر في [1، 2، 3]."
                    )
                }
                for ((index, element) in iterable.withIndex()) {
                    val loopEnv = Environment(environment)
                    stmt.indexVar?.let { loopEnv.define(it.lexeme, index.toDouble(), false) }
                    loopEnv.define(stmt.elementVar.lexeme, element, false)
                    try {
                        executeBlock((stmt.body as Stmt.Block).statements, loopEnv)
                    } catch (_: BreakSignal) {
                        break
                    } catch (_: ContinueSignal) {
                        continue
                    }
                }
            }

            is Stmt.Break -> throw BreakSignal()
            is Stmt.Continue -> throw ContinueSignal()

            is Stmt.Let -> {
                val value = stmt.initializer?.let { evaluate(it) }
                defineVariables(stmt.names, value, false)
            }

            is Stmt.Const -> {
                val value = evaluate(stmt.initializer)
                val finalValue = if (value is SakhrInstance) value.asImmutable() else value
                defineVariables(stmt.names, finalValue, true)
            }

            is Stmt.Return -> {
                val value = stmt.value?.let { evaluate(it) }
                throw Return(value)
            }

            is Stmt.Raise -> {
                val errorValue = evaluate(stmt.message)
                throw SakhrRaiseException(errorValue)
            }
        }
    }

    private fun defineVariables(names: List<Token>, value: Any?, isConstant: Boolean) {
        if (names.size > 1) {
            if (value is SakhrResult) {
                environment.define(names[0].lexeme, value.value, isConstant)
                environment.define(names[1].lexeme, value.error, isConstant)
                // Any extra names get null
                for (i in 2 until names.size) {
                    environment.define(names[i].lexeme, null, isConstant)
                }
            } else {
                // If it's not a SakhrResult, first variable gets the value, others null
                environment.define(names[0].lexeme, value, isConstant)
                for (i in 1 until names.size) {
                    environment.define(names[i].lexeme, null, isConstant)
                }
            }
        } else if (names.isNotEmpty()) {
            val finalValue = if (value is SakhrResult) {
                if (value.error != null) {
                    // Unhandled error
                    throw SakhrError.RuntimeError(
                        "أُطلق خطأ لم تتم معالجته: ${stringify(value.error)}",
                        names[0].location,
                        "استقبل الخطأ في متغير ثانٍ لمعالجته، مثل: ليكن النتيجة، الخطأ = ..."
                    )
                }
                value.value
            } else {
                value
            }
            environment.define(names[0].lexeme, finalValue, isConstant)
        }
    }

    fun executeBlock(statements: List<Stmt>, environment: Environment) {
        val previous = this.environment
        try {
            this.environment = environment
            for (stmt in statements) {
                execute(stmt)
            }
        } finally {
            this.environment = previous
        }
    }

    fun evaluate(expr: Expr): Any? {
        return when (expr) {
            is Expr.Binary -> {
                val left = evaluate(expr.left)
                val right = evaluate(expr.right)
                when (expr.operator.type) {
                    TokenType.GREATER -> numOp(expr.operator, left, right) { l, r -> l > r }
                    TokenType.GREATER_EQUALS -> numOp(expr.operator, left, right) { l, r -> l >= r }
                    TokenType.LESS -> numOp(expr.operator, left, right) { l, r -> l < r }
                    TokenType.LESS_EQUALS -> numOp(expr.operator, left, right) { l, r -> l <= r }

                    TokenType.EQUALS_EQUALS -> left == right
                    TokenType.BANG_EQUALS -> left != right
                    TokenType.PLUS -> {
                        if (left is Double && right is Double) left + right
                        else if (left is String || right is String) stringify(left) + stringify(
                            right
                        )
                        else throw SakhrError.RuntimeError(
                            "لا يمكن تطبيق العملية '+' بين قيمة من نوع '${getSakhrTypeName(left)}' وأخرى من نوع '${getSakhrTypeName(right)}'.",
                            expr.operator.location,
                            "يُستخدم '+' لجمع الأرقام أو لدمج النصوص فقط."
                        )
                    }

                    TokenType.MINUS -> numOp(expr.operator, left, right) { l, r -> l - r }
                    TokenType.STAR -> numOp(expr.operator, left, right) { l, r -> l * r }

                    TokenType.SLASH -> numOp(expr.operator, left, right) { l, r ->
                        if (r == 0.0) throw SakhrError.RuntimeError(
                            "القسمة على صفر غير معرّفة.",
                            expr.operator.location,
                            "تأكد من أن المقسوم عليه لا يساوي صفراً قبل إجراء القسمة."
                        )
                        l / r
                    }

                    TokenType.PERCENT -> numOp(expr.operator, left, right) { l, r ->
                        if (r == 0.0) throw SakhrError.RuntimeError(
                            "حساب باقي القسمة على صفر غير معرّف.",
                            expr.operator.location,
                            "تأكد من أن المقسوم عليه لا يساوي صفراً قبل حساب الباقي."
                        )
                        l % r
                    }

                    else -> throw SakhrError.RuntimeError(
                        "العملية '${expr.operator.lexeme}' غير مدعومة.",
                        expr.operator.location,
                        length = expr.operator.lexeme.length
                    )
                }
            }

            is Expr.Logical -> {
                val left = evaluate(expr.left)
                when (expr.operator.type) {
                    TokenType.OR -> if (isTruthy(left)) left else evaluate(expr.right)
                    TokenType.AND -> if (!isTruthy(left)) left else evaluate(expr.right)
                    else -> throw SakhrError.RuntimeError(
                        "العملية المنطقية '${expr.operator.lexeme}' غير مدعومة.",
                        expr.operator.location,
                        length = expr.operator.lexeme.length
                    )
                }
            }

            is Expr.Unary -> {
                val right = evaluate(expr.right)
                when (expr.operator.type) {
                    TokenType.MINUS -> -checkNumberOperand(expr.operator, right)
                    TokenType.NOT -> !isTruthy(right)
                    else -> throw SakhrError.RuntimeError(
                        "العملية الأحادية '${expr.operator.lexeme}' غير مدعومة.",
                        expr.operator.location,
                        length = expr.operator.lexeme.length
                    )
                }
            }

            is Expr.ListLiteral -> expr.elements.mapTo(ArrayList(expr.elements.size)) { evaluate(it) }

            is Expr.Index -> {
                val obj = evaluate(expr.obj)
                val indexDouble = evaluate(expr.index) as? Double ?: throw SakhrError.RuntimeError(
                    "الفهرس يجب أن يكون رقماً، لا قيمة من نوع آخر.",
                    expr.bracket.location,
                    "استخدم رقماً صحيحاً بين قوسي الفهرسة، مثل: القائمة[0]."
                )
                val index = indexDouble.toInt()

                if (obj !is List<*>) {
                    throw SakhrError.RuntimeError(
                        "لا يمكن استخدام الفهرسة إلا مع القوائم، والقيمة المعطاة من نوع '${getSakhrTypeName(obj)}'.",
                        expr.bracket.location
                    )
                }

                if (index < 0 || index >= obj.size) {
                    throw SakhrError.RuntimeError(
                        "الفهرس ($index) خارج حدود القائمة، وعدد عناصرها ${obj.size}.",
                        expr.bracket.location,
                        if (obj.isEmpty()) "القائمة فارغة، لذا لا يمكن الوصول إلى أي عنصر فيها."
                        else "الفهارس الصالحة تتراوح بين 0 و ${obj.size - 1}."
                    )
                }

                val result = obj[index]
                if (result is SakhrInstance) return result.asImmutable()
                result
            }

            is Expr.Call -> {
                val callee = evaluate(expr.callee)

                val arguments = mutableListOf<Any?>()
                val namedArguments = mutableMapOf<String, Any?>()

                for (argExpr in expr.arguments) {
                    if (argExpr is Expr.Assignment) {
                        namedArguments[argExpr.name.lexeme] = evaluate(argExpr.value)
                    } else {
                        arguments.add(evaluate(argExpr))
                    }
                }

                if (callee !is SakhrCallable) {
                    throw SakhrError.RuntimeError(
                        "لا يمكن استدعاء قيمة من نوع '${getSakhrTypeName(callee)}'؛ الاستدعاء يقتصر على الدوال.",
                        expr.paren.location
                    )
                }

                val totalArgs = arguments.size + namedArguments.size
                if (callee !is SakhrStruct && (totalArgs < callee.minArity() || totalArgs > callee.maxArity())) {
                    val expectedStr = if (callee.minArity() == callee.maxArity()) {
                        "${callee.arity()}"
                    } else {
                        "بين ${callee.minArity()} و ${callee.maxArity()}"
                    }
                    throw SakhrError.RuntimeError(
                        "عدد الوسائط غير مطابق؛ الدالة تتوقع $expectedStr من الوسائط بينما تم تمرير $totalArgs.",
                        expr.paren.location
                    )
                }

                callee.call(this, arguments, namedArguments, expr.paren.location)
            }

            is Expr.Get -> {
                val obj = evaluate(expr.obj)

                if (obj is SakhrInstance) {
                    if (obj.fields.containsKey(expr.name.lexeme)) {
                        return obj.fields[expr.name.lexeme]
                    }
                }

                if (obj is SakhrEnum) {
                    if (obj.members.contains(expr.name.lexeme)) {
                        return SakhrEnumValue(obj, expr.name.lexeme)
                    }
                }

                val typeName = getSakhrTypeName(obj)
                val methodName = expr.name.lexeme

                val function = globals.getRaw("${typeName}::${methodName}")
                if (function is SakhrExtension) {
                    return object : SakhrCallable {
                        override fun arity(): Int = function.arity()
                        override fun call(
                            interpreter: Interpreter,
                            arguments: List<Any?>,
                            namedArguments: Map<String, Any?>,
                            location: Location
                        ): Any? =
                            function.callWithContext(interpreter, arguments, namedArguments, obj, location)
                    }
                }



                throw SakhrError.RuntimeError(
                    "النوع '${typeName}' لا يملك حقلاً أو دالة ممتدة باسم '${methodName}'.",
                    expr.name.location,
                    if (obj is SakhrInstance)
                        DiagnosticEngine.findClosest(methodName, obj.fields.keys)
                            ?.let { "هل قصدت الحقل '$it'؟" }
                    else null,
                    length = methodName.length
                )
            }

            is Expr.Set -> {
                val obj = evaluate(expr.obj)
                if (obj !is SakhrInstance) {
                    throw SakhrError.RuntimeError(
                        "لا يمكن تعيين قيمة إلا لحقول البنى، والقيمة المستهدفة من نوع '${getSakhrTypeName(obj)}'.",
                        expr.name.location,
                        length = expr.name.lexeme.length
                    )
                }

                if (!obj.isMutable) {
                    throw SakhrError.RuntimeError(
                        "لا يمكن تعديل الحقل '${expr.name.lexeme}' لأن الكائنات المستخرجة من القوائم ثابتة (غير قابلة للتغيير).",
                        expr.name.location,
                        "لتحديث عنصر في قائمة استخدم الدالة 'استبدل(الفهرس، القيمة_الجديدة)'.",
                        length = expr.name.lexeme.length
                    )
                }

                if (!obj.fields.containsKey(expr.name.lexeme)) {
                    throw SakhrError.RuntimeError(
                        "البنية '${obj.struct.declaration.name.lexeme}' لا تملك حقلاً باسم '${expr.name.lexeme}'.",
                        expr.name.location,
                        DiagnosticEngine.findClosest(expr.name.lexeme, obj.fields.keys)
                            ?.let { "هل قصدت الحقل '$it'؟" },
                        length = expr.name.lexeme.length
                    )
                }

                val value = evaluate(expr.value)

                // Validate type
                val typeName = getSakhrTypeName(value)
                val expectedType = obj.struct.getFieldType(expr.name.lexeme)
                if (expectedType != null && typeName != expectedType && value != null) {
                    throw SakhrError.RuntimeError(
                        "لا يمكن إسناد قيمة من نوع '$typeName' إلى الحقل '${expr.name.lexeme}' لأنه معرّف بالنوع '$expectedType'.",
                        expr.name.location,
                        length = expr.name.lexeme.length
                    )
                }

                obj.fields[expr.name.lexeme] = value
                value
            }

            is Expr.Grouping -> evaluate(expr.expression)
            is Expr.Literal -> expr.value
            is Expr.Variable -> environment.get(expr.name)
            is Expr.Context -> environment.get(expr.keyword)
            is Expr.Assignment -> {
                val value = evaluate(expr.value)
                environment.assign(expr.name, value)
                value
            }
        }
    }

    fun evaluateInEnvironment(expr: Expr, environment: Environment): Any? {
        val previous = this.environment
        try {
            this.environment = environment
            return evaluate(expr)
        } finally {
            this.environment = previous
        }
    }

    fun stringify(obj: Any?): String {
        if (obj == null) return "فارغ"
        if (obj == SakhrUnit) return "عدم"

        if (obj is Boolean)
            return if (obj) "صح" else "خطأ"

        if (obj is Double) {
            var text = obj.toString()
            if (text.endsWith(".0"))
                text = text.substring(0, text.length - 2)
            return text
        }

        if (obj is List<*>)
            return obj.joinToString(prefix = "[", postfix = "]", separator = "، ") { stringify(it) }

        if (obj is SakhrInstance) return obj.stringify(this)
        
        if (obj is SakhrEnumValue) return obj.name

        return obj.toString()
    }

    fun getSakhrTypeName(obj: Any?): String {
        return when (obj) {
            null -> "فارغ"
            SakhrUnit -> "عدم"
            is String -> "نص"
            is Double -> "رقم"
            is Boolean -> "منطقي"
            is List<*> -> "قائمة"
            is SakhrInstance -> obj.struct.declaration.name.lexeme
            is SakhrEnum -> obj.name
            is SakhrEnumValue -> obj.enum.name
            else -> "مجهول"
        }
    }

    fun isTruthy(obj: Any?): Boolean {
        if (obj == null) return false
        if (obj is Boolean) return obj
        return true
    }

    private fun checkNumberOperand(operator: Token, operand: Any?): Double {
        if (operand is Double) return operand
        throw SakhrError.RuntimeError(
            "العملية '${operator.lexeme}' تتطلب قيمة رقمية، والقيمة المعطاة من نوع '${getSakhrTypeName(operand)}'.",
            operator.location,
            length = operator.lexeme.length
        )
    }

    // Applies [block] to both operands as numbers without allocating a Pair.
    // Inlined so the lambda and the smart-cast operands stay on the stack.
    private inline fun <R> numOp(
        operator: Token,
        left: Any?,
        right: Any?,
        block: (Double, Double) -> R
    ): R {
        if (left is Double && right is Double) return block(left, right)
        throw SakhrError.RuntimeError(
            "العملية '${operator.lexeme}' تتطلب رقمين على جانبيها.",
            operator.location,
            length = operator.lexeme.length
        )
    }
}
