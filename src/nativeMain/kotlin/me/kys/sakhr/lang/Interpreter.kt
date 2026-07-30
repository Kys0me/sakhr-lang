package me.kys.sakhr.lang

import kotlin.system.exitProcess

interface SakhrCallable {
    fun arity(): Int
    fun call(interpreter: Interpreter, arguments: List<Any?>): Any?
}

interface SakhrExtension : SakhrCallable {
    fun callWithContext(interpreter: Interpreter, arguments: List<Any?>, context: Any?): Any?
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

    override fun call(interpreter: Interpreter, arguments: List<Any?>): Any? =
        callWithContext(interpreter, arguments, null)

    override fun callWithContext(interpreter: Interpreter, arguments: List<Any?>, context: Any?): Any? {
        val environment = Environment(closure)
        if (isExtension && context != null) {
            environment.define("السياق", context, true)
        }
        for ((i, element) in declaration.params.withIndex()) {
            environment.define(element.name.lexeme, arguments[i], false)
        }

        try {
            interpreter.executeBlock(declaration.body, environment)
        } catch (returnValue: Return) {
            return returnValue.value
        }
        return null
    }
}

class Interpreter(private val diagnostics: DiagnosticEngine) : Backend {
    val globals = Environment()
    private var environment = globals

    init {
        globals.define("أكتب", object : SakhrCallable {
            override fun arity(): Int = 1
            override fun call(interpreter: Interpreter, arguments: List<Any?>): Any? {
                println(stringify(arguments[0]))
                return null
            }
        }, true)

        globals.define("إنهاء_البرنامج", object : SakhrCallable {
            override fun arity(): Int = 1
            override fun call(interpreter: Interpreter, arguments: List<Any?>): Any? {
                val code = (arguments[0] as? Double)?.toInt() ?: 0
                exitProcess(code)
            }
        }, true)

        globals.define("اقرأ", object : SakhrCallable {
            override fun arity(): Int = 0
            override fun call(interpreter: Interpreter, arguments: List<Any?>): Any? = readlnOrNull()
        }, true)

        globals.define("رقم", object : SakhrCallable {
            override fun arity(): Int = 1
            override fun call(interpreter: Interpreter, arguments: List<Any?>): Any {
                val arg = arguments[0]
                if (arg is Double) return arg
                if (arg is Boolean) return if (arg) 1.0 else 0.0
                if (arg is String) {
                    return arg.toDoubleOrNull() ?: throw SakhrError.RuntimeError(
                        "لا يمكن تحويل النص '$arg' إلى رقم.",
                        Location(0, 0)
                    )
                }
                throw SakhrError.RuntimeError("لا يمكن تحويل النوع ${getSakhrTypeName(arg)} إلى رقم.", Location(0, 0))
            }
        }, true)

        globals.define("نص", object : SakhrCallable {
            override fun arity(): Int = 1
            override fun call(interpreter: Interpreter, arguments: List<Any?>): Any = stringify(arguments[0])
        }, true)

        globals.define("منطقي", object : SakhrCallable {
            override fun arity(): Int = 1
            override fun call(interpreter: Interpreter, arguments: List<Any?>): Any = isTruthy(arguments[0])
        }, true)

        // Built-in extension methods
        defineBuiltInExtension("رقم", "نص", 0) { _, _, context -> stringify(context) }
        defineBuiltInExtension("منطقي", "نص", 0) { _, _, context -> stringify(context) }
        defineBuiltInExtension("نص", "نص", 0) { _, _, context -> stringify(context) }
        defineBuiltInExtension("قائمة", "نص", 0) { _, _, context -> stringify(context) }

        defineBuiltInExtension("نص", "طول", 0) { _, _, context -> (context as String).length.toDouble() }
        defineBuiltInExtension("قائمة", "حجم", 0) { _, _, context -> (context as List<*>).size.toDouble() }

        defineBuiltInExtension("قائمة", "أضف", 1) { _, args, context ->
            @Suppress("UNCHECKED_CAST")
            (context as MutableList<Any?>).add(args[0])
            null
        }

        defineBuiltInExtension("قائمة", "أزل", 1) { _, args, context ->
            @Suppress("UNCHECKED_CAST")
            val list = context as MutableList<Any?>
            val arg = args[0]
            if (arg is Double) {
                val index = arg.toInt()
                if (index in list.indices) {
                    list.removeAt(index)
                }
            } else {
                list.remove(arg)
            }
            null
        }

        defineBuiltInExtension("قائمة", "أدخل", 2) { _, args, context ->
            @Suppress("UNCHECKED_CAST")
            val list = context as MutableList<Any?>
            val index = (args[0] as? Double)?.toInt() ?: throw SakhrError.RuntimeError("يجب أن يكون الفهرس رقماً.", Location(0, 0))
            if (index in 0..list.size) {
                list.add(index, args[1])
            }
            null
        }
    }

    private fun defineBuiltInExtension(
        typeName: String,
        methodName: String,
        arity: Int,
        call: (Interpreter, List<Any?>, Any?) -> Any?
    ) {
        globals.define("${typeName}::${methodName}", object : SakhrExtension {
            override fun arity(): Int = arity
            override fun call(interpreter: Interpreter, arguments: List<Any?>): Any =
                throw SakhrError.RuntimeError("لا يمكن استدعاء الدالة الممتدة '${methodName}' مباشرة.", Location(0, 0))

            override fun callWithContext(
                interpreter: Interpreter,
                arguments: List<Any?>,
                context: Any?
            ): Any? = call(interpreter, arguments, context)
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
                    globals.define("${stmt.receiverType.lexeme}::${stmt.name.lexeme}", function, true)
                } else {
                    globals.define(stmt.name.lexeme, function, true)
                }
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
                environment.define(stmt.name.lexeme, value, true)
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

    private fun evaluate(expr: Expr): Any? {
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
                        else if (left is String || right is String) stringify(left) + stringify(right)
                        else throw SakhrError.RuntimeError("العملية '+' غير مدعومة بين هذه الأنواع.", expr.operator.location)
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
                        if (r == 0.0) throw SakhrError.RuntimeError("لا يمكن القسمة على صفر.", expr.operator.location)
                        l / r
                    }
                    TokenType.PERCENT -> {
                        val (l, r) = checkNumberOperands(expr.operator, left, right)
                        if (r == 0.0) throw SakhrError.RuntimeError("لا يمكن حساب باقي القسمة على صفر.", expr.operator.location)
                        l % r
                    }
                    else -> throw SakhrError.RuntimeError("عملية غير مدعومة '${expr.operator.lexeme}'.", expr.operator.location)
                }
            }
            is Expr.Logical -> {
                val left = evaluate(expr.left)
                when (expr.operator.type) {
                    TokenType.OR -> if (isTruthy(left)) left else evaluate(expr.right)
                    TokenType.AND -> if (!isTruthy(left)) left else evaluate(expr.right)
                    else -> throw SakhrError.RuntimeError("عملية منطقية غير مدعومة '${expr.operator.lexeme}'.", expr.operator.location)
                }
            }
            is Expr.Unary -> {
                val right = evaluate(expr.right)
                when (expr.operator.type) {
                    TokenType.MINUS -> -checkNumberOperand(expr.operator, right)
                    TokenType.NOT -> !isTruthy(right)
                    else -> throw SakhrError.RuntimeError("عملية أحادية غير مدعومة '${expr.operator.lexeme}'.", expr.operator.location)
                }
            }
            is Expr.ListLiteral -> expr.elements.map { evaluate(it) }.toMutableList()

            is Expr.Call -> {
                val callee = evaluate(expr.callee)
                val arguments = expr.arguments.map { evaluate(it) }

                if (callee !is SakhrCallable) {
                    throw SakhrError.RuntimeError("يُسمح باستدعاء الدوال فقط.", expr.paren.location)
                }

                if (arguments.size != callee.arity()) {
                    throw SakhrError.RuntimeError("تتوقع الدالة ${callee.arity()} من الوسائط، ولكن تم تمرير ${arguments.size}.", expr.paren.location)
                }

                callee.call(this, arguments)
            }
            is Expr.Get -> {
                val obj = evaluate(expr.obj)
                val typeName = getSakhrTypeName(obj)
                val methodName = expr.name.lexeme
                
                val function = globals.getRaw("${typeName}::${methodName}")
                if (function is SakhrExtension) {
                    return object : SakhrCallable {
                        override fun arity(): Int = function.arity()
                        override fun call(interpreter: Interpreter, arguments: List<Any?>): Any? =
                            function.callWithContext(interpreter, arguments, obj)
                    }
                }

                if (obj is List<*> && methodName == "خذ") {
                     return object : SakhrCallable {
                         override fun arity() = 1
                         override fun call(interpreter: Interpreter, arguments: List<Any?>): Any? {
                             val indexDouble = arguments[0] as? Double ?: throw SakhrError.RuntimeError("يجب أن يكون الفهرس من النوع 'رقم'.", expr.name.location)
                             val index = indexDouble.toInt()
                             if (index < 0 || index >= obj.size) {
                                 throw SakhrError.RuntimeError(
                                     "الفهرس ($index) خارج النطاق المسموح به؛ حجم القائمة هو ${obj.size}.",
                                     expr.name.location,
                                     if (obj.isEmpty()) "القائمة فارغة، لا يمكنك استخدام 'خذ' هنا." else "استخدم فهرساً بين 0 و ${obj.size - 1}."
                                 )
                             }
                             return obj[index]
                         }
                     }
                }

                throw SakhrError.RuntimeError("تعذر العثور على الدالة الممتدة '${methodName}' للنوع '${typeName}'.", expr.name.location)
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

    private fun stringify(obj: Any?): String {
        if (obj == null) return "عدم"
        if (obj is Boolean) return if (obj) "صح" else "خطأ"
        if (obj is Double) {
            var text = obj.toString()
            if (text.endsWith(".0")) {
                text = text.substring(0, text.length - 2)
            }
            return text
        }
        if (obj is List<*>) {
            return obj.joinToString(prefix = "[", postfix = "]", separator = "، ") { stringify(it) }
        }
        return obj.toString()
    }

    private fun getSakhrTypeName(obj: Any?): String {
        return when (obj) {
            is String -> "نص"
            is Double -> "رقم"
            is Boolean -> "منطقي"
            is List<*> -> "قائمة"
            else -> "عدم"
        }
    }

    private fun isTruthy(obj: Any?): Boolean {
        if (obj == null) return false
        if (obj is Boolean) return obj
        return true
    }

    private fun checkNumberOperand(operator: Token, operand: Any?): Double {
        if (operand is Double) return operand
        throw SakhrError.RuntimeError("العملية '${operator.lexeme}' تتطلب رقماً.", operator.location)
    }

    private fun checkNumberOperands(operator: Token, left: Any?, right: Any?): Pair<Double, Double> {
        if (left is Double && right is Double) return Pair(left, right)
        throw SakhrError.RuntimeError("العملية '${operator.lexeme}' تتطلب أرقاماً.", operator.location)
    }
}
