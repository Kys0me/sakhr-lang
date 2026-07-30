package me.kys.sakhr.lang

interface SakhrCallable {
    fun arity(): Int
    fun minArity(): Int = arity()
    fun maxArity(): Int = arity()
    fun call(
        interpreter: Interpreter,
        arguments: List<Any?>,
        namedArguments: Map<String, Any?> = emptyMap()
    ): Any?
}

interface SakhrExtension : SakhrCallable {
    fun callWithContext(
        interpreter: Interpreter,
        arguments: List<Any?>,
        namedArguments: Map<String, Any?>,
        context: Any?
    ): Any?
}

class Return(val value: Any?) : RuntimeException()
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
        namedArguments: Map<String, Any?>
    ): Any? =
        callWithContext(interpreter, arguments, namedArguments, null)

    override fun callWithContext(
        interpreter: Interpreter,
        arguments: List<Any?>,
        namedArguments: Map<String, Any?>,
        context: Any?
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
            return returnValue.value
        }
        return null
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
        namedArguments: Map<String, Any?>
    ): Any {
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
                ?: throw SakhrError.RuntimeError(
                    "البنية '${declaration.name.lexeme}' لا تحتوي على حقل باسم '$name'.",
                    Location(0, 0)
                )
            validateAndSetField(instance, field.name, value, interpreter)
        }

        return instance
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
                "نوع الحقل '$name' هو '${expectedType}'، ولكن تم تمرير قيمة من نوع '$typeName'.",
                fieldName.location
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

class Interpreter(private val diagnostics: DiagnosticEngine) : Backend {
    val globals = Environment()
    private var environment = globals

    init {
        for (func in BuiltIns.functions) {
            globals.define(func.name, object : SakhrCallable {
                override fun arity(): Int = func.params.size
                override fun call(
                    interpreter: Interpreter,
                    arguments: List<Any?>,
                    namedArguments: Map<String, Any?>
                ): Any? =
                    func.call(interpreter, arguments, namedArguments)
            }, true)
        }

        for (ext in BuiltIns.extensions) {
            defineBuiltInExtension(
                ext.receiverType.lexeme,
                ext.name,
                ext.params.size
            ) { interpreter, args, namedArgs, context ->
                ext.call(interpreter, args, namedArgs, context)
            }
        }
    }

    private fun defineBuiltInExtension(
        typeName: String,
        methodName: String,
        arity: Int,
        call: (Interpreter, List<Any?>, Map<String, Any?>, Any?) -> Any?
    ) {
        globals.define("${typeName}::${methodName}", object : SakhrExtension {
            override fun arity(): Int = arity
            override fun call(
                interpreter: Interpreter,
                arguments: List<Any?>,
                namedArguments: Map<String, Any?>
            ): Any =
                throw SakhrError.RuntimeError(
                    "لا يمكن استدعاء الدالة الممتدة '${methodName}' مباشرة.",
                    Location(0, 0)
                )

            override fun callWithContext(
                interpreter: Interpreter,
                arguments: List<Any?>,
                namedArguments: Map<String, Any?>,
                context: Any?
            ): Any? = call(interpreter, arguments, namedArguments, context)
        }, true)
    }

    override fun execute(statements: List<Stmt>) {
        try {
            for (stmt in statements) {
                execute(stmt)
            }
        } catch (error: SakhrError.RuntimeError) {
            diagnostics.report(error)
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

            is Stmt.If -> {
                if (isTruthy(evaluate(stmt.condition))) {
                    execute(stmt.thenBranch)
                } else if (stmt.elseBranch != null) {
                    execute(stmt.elseBranch)
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
                        "لا يمكن التكرار إلا على قائمة.",
                        stmt.elementVar.location
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
                environment.define(stmt.name.lexeme, value, false)
            }

            is Stmt.Const -> {
                val value = evaluate(stmt.initializer)
                if (value is SakhrInstance) {
                    // Force immutability for constants
                    val immutableValue = value.asImmutable()
                    environment.define(stmt.name.lexeme, immutableValue, true)
                } else {
                    environment.define(stmt.name.lexeme, value, true)
                }
            }

            is Stmt.Return -> {
                val value = stmt.value?.let { evaluate(it) }
                throw Return(value)
            }
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
                    TokenType.GREATER -> {
                        val (l, r) = checkNumberOperands(expr.operator, left, right)
                        l > r
                    }

                    TokenType.GREATER_EQUALS -> {
                        val (l, r) = checkNumberOperands(expr.operator, left, right)
                        l >= r
                    }

                    TokenType.LESS -> {
                        val (l, r) = checkNumberOperands(expr.operator, left, right)
                        l < r
                    }

                    TokenType.LESS_EQUALS -> {
                        val (l, r) = checkNumberOperands(expr.operator, left, right)
                        l <= r
                    }

                    TokenType.EQUALS_EQUALS -> left == right
                    TokenType.BANG_EQUALS -> left != right
                    TokenType.PLUS -> {
                        if (left is Double && right is Double) left + right
                        else if (left is String || right is String) stringify(left) + stringify(
                            right
                        )
                        else throw SakhrError.RuntimeError(
                            "العملية '+' غير مدعومة بين هذه الأنواع.",
                            expr.operator.location
                        )
                    }

                    TokenType.MINUS -> {
                        val (l, r) = checkNumberOperands(expr.operator, left, right)
                        l - r
                    }

                    TokenType.STAR -> {
                        val (l, r) = checkNumberOperands(expr.operator, left, right)
                        l * r
                    }

                    TokenType.SLASH -> {
                        val (l, r) = checkNumberOperands(expr.operator, left, right)
                        if (r == 0.0) throw SakhrError.RuntimeError(
                            "لا يمكن القسمة على صفر.",
                            expr.operator.location
                        )
                        l / r
                    }

                    TokenType.PERCENT -> {
                        val (l, r) = checkNumberOperands(expr.operator, left, right)
                        if (r == 0.0) throw SakhrError.RuntimeError(
                            "لا يمكن حساب باقي القسمة على صفر.",
                            expr.operator.location
                        )
                        l % r
                    }

                    else -> throw SakhrError.RuntimeError(
                        "عملية غير مدعومة '${expr.operator.lexeme}'.",
                        expr.operator.location
                    )
                }
            }

            is Expr.Logical -> {
                val left = evaluate(expr.left)
                when (expr.operator.type) {
                    TokenType.OR -> if (isTruthy(left)) left else evaluate(expr.right)
                    TokenType.AND -> if (!isTruthy(left)) left else evaluate(expr.right)
                    else -> throw SakhrError.RuntimeError(
                        "عملية منطقية غير مدعومة '${expr.operator.lexeme}'.",
                        expr.operator.location
                    )
                }
            }

            is Expr.Unary -> {
                val right = evaluate(expr.right)
                when (expr.operator.type) {
                    TokenType.MINUS -> -checkNumberOperand(expr.operator, right)
                    TokenType.NOT -> !isTruthy(right)
                    else -> throw SakhrError.RuntimeError(
                        "عملية أحادية غير مدعومة '${expr.operator.lexeme}'.",
                        expr.operator.location
                    )
                }
            }

            is Expr.ListLiteral -> expr.elements.map { evaluate(it) }.toMutableList()

            is Expr.Index -> {
                val obj = evaluate(expr.obj)
                val indexDouble = evaluate(expr.index) as? Double ?: throw SakhrError.RuntimeError(
                    "يجب أن يكون الفهرس رقماً.",
                    expr.bracket.location
                )
                val index = indexDouble.toInt()

                if (obj !is List<*>) {
                    throw SakhrError.RuntimeError(
                        "لا يمكن استخدام الفهرسة إلا مع القوائم.",
                        expr.bracket.location
                    )
                }

                if (index < 0 || index >= obj.size) {
                    throw SakhrError.RuntimeError(
                        "الفهرس ($index) خارج النطاق المسموح به؛ حجم القائمة هو ${obj.size}.",
                        expr.bracket.location
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
                    throw SakhrError.RuntimeError("يُسمح باستدعاء الدوال فقط.", expr.paren.location)
                }

                val totalArgs = arguments.size + namedArguments.size
                if (callee !is SakhrStruct && (totalArgs < callee.minArity() || totalArgs > callee.maxArity())) {
                    val expectedStr = if (callee.minArity() == callee.maxArity()) {
                        "${callee.arity()}"
                    } else {
                        "بين ${callee.minArity()} و ${callee.maxArity()}"
                    }
                    throw SakhrError.RuntimeError(
                        "تتوقع الدالة $expectedStr من الوسائط، ولكن تم تمرير $totalArgs.",
                        expr.paren.location
                    )
                }

                callee.call(this, arguments, namedArguments)
            }

            is Expr.Get -> {
                val obj = evaluate(expr.obj)

                if (obj is SakhrInstance) {
                    if (obj.fields.containsKey(expr.name.lexeme)) {
                        return obj.fields[expr.name.lexeme]
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
                            namedArguments: Map<String, Any?>
                        ): Any? =
                            function.callWithContext(interpreter, arguments, namedArguments, obj)
                    }
                }



                throw SakhrError.RuntimeError(
                    "تعذر العثور على الحقل أو الدالة الممتدة '${methodName}' للنوع '${typeName}'.",
                    expr.name.location
                )
            }

            is Expr.Set -> {
                val obj = evaluate(expr.obj)
                if (obj !is SakhrInstance) {
                    throw SakhrError.RuntimeError(
                        "لا يمكن التعيين إلا لحقول بنية.",
                        expr.name.location
                    )
                }

                if (!obj.isMutable) {
                    throw SakhrError.RuntimeError(
                        "لا يمكن تعديل الحقل '${expr.name.lexeme}' لأن الكائنات المستخرجة من القوائم ثابتة (غير قابلة للتغيير). يُنصح باستخدام الدالة 'استبدل(الفهرس، القيمة_الجديدة)' لتحديث القائمة بدلاً من ذلك.",
                        expr.name.location
                    )
                }

                if (!obj.fields.containsKey(expr.name.lexeme)) {
                    throw SakhrError.RuntimeError(
                        "البنية '${obj.struct.declaration.name.lexeme}' لا تحتوي على حقل باسم '${expr.name.lexeme}'.",
                        expr.name.location
                    )
                }

                val value = evaluate(expr.value)

                // Validate type
                val typeName = getSakhrTypeName(value)
                val expectedType = obj.struct.getFieldType(expr.name.lexeme)
                if (expectedType != null && typeName != expectedType && value != null) {
                    throw SakhrError.RuntimeError(
                        "نوع الحقل '${expr.name.lexeme}' هو '$expectedType'، ولكن تم تعيين قيمة من نوع '$typeName'.",
                        expr.name.location
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
        if (obj == null) return "عدم"

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

        return obj.toString()
    }

    fun getSakhrTypeName(obj: Any?): String {
        return when (obj) {
            is String -> "نص"
            is Double -> "رقم"
            is Boolean -> "منطقي"
            is List<*> -> "قائمة"
            is SakhrInstance -> obj.struct.declaration.name.lexeme
            else -> "عدم"
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
            "العملية '${operator.lexeme}' تتطلب رقماً.",
            operator.location
        )
    }

    private fun checkNumberOperands(
        operator: Token,
        left: Any?,
        right: Any?
    ): Pair<Double, Double> {
        if (left is Double && right is Double) return Pair(left, right)
        throw SakhrError.RuntimeError(
            "العملية '${operator.lexeme}' تتطلب أرقاماً.",
            operator.location
        )
    }
}
