package me.kys.sakhr.lang

import kotlin.system.exitProcess

interface SakhrCallable {
    fun arity(): Int
    fun call(interpreter: Interpreter, arguments: List<Any?>): Any?
}

class Return(val value: Any?) : RuntimeException()

class SakhrFunction(
    private val declaration: Stmt.Function,
    private val closure: Environment,
    private val isExtension: Boolean
) : SakhrCallable {
    override fun arity(): Int = declaration.params.size

    override fun call(interpreter: Interpreter, arguments: List<Any?>): Any? {
        return callWithContext(interpreter, arguments, null)
    }

    fun callWithContext(interpreter: Interpreter, arguments: List<Any?>, context: Any?): Any? {
        val environment = Environment(closure)
        if (isExtension && context != null) {
            environment.define("السياق", context, true)
        }
        for (i in 0 until declaration.params.size) {
            environment.define(declaration.params[i].name.lexeme, arguments[i], false)
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
            is Stmt.Let -> {
                val value = stmt.initializer?.let { evaluate(it) }
                environment.define(stmt.name.lexeme, value, false)
            }
            is Stmt.Const -> {
                val value = evaluate(stmt.initializer)
                environment.define(stmt.name.lexeme, value, true)
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
                    TokenType.GREATER -> (left as Double) > (right as Double)
                    TokenType.LESS -> (left as Double) < (right as Double)
                    TokenType.EQUALS_EQUALS -> left == right
                    TokenType.BANG_EQUALS -> left != right
                    TokenType.PLUS -> {
                        if (left is Double && right is Double) left + right
                        else if (left is String || right is String) stringify(left) + stringify(right)
                        else throw SakhrError.RuntimeError("العملية '+' غير مدعومة بين هذه الأنواع.", expr.operator.location)
                    }
                    TokenType.MINUS -> (left as Double) - (right as Double)
                    TokenType.STAR -> (left as Double) * (right as Double)
                    TokenType.SLASH -> (left as Double) / (right as Double)
                    else -> null
                }
            }
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
                if (function is SakhrFunction) {
                    return object : SakhrCallable {
                        override fun arity(): Int = function.arity()
                        override fun call(interpreter: Interpreter, arguments: List<Any?>): Any? {
                            return function.callWithContext(interpreter, arguments, obj)
                        }
                    }
                }
                
                if (obj is List<*> && methodName == "حجم") {
                    return obj.size.toDouble()
                }

                if (obj is List<*> && methodName == "خذ") {
                     return object : SakhrCallable {
                         override fun arity() = 1
                         override fun call(interpreter: Interpreter, arguments: List<Any?>): Any? {
                             val index = (arguments[0] as? Double)?.toInt() ?: throw SakhrError.RuntimeError("يجب أن يكون الفهرس من النوع 'رقم'.", expr.name.location)
                             if (index < 0 || index >= obj.size) throw SakhrError.RuntimeError("الفهرس خارج النطاق المسموح به.", expr.name.location)
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
        if (obj is Double) {
            var text = obj.toString()
            if (text.endsWith(".0")) {
                text = text.substring(0, text.length - 2)
            }
            return text
        }
        return obj.toString()
    }

    private fun getSakhrTypeName(obj: Any?): String {
        return when (obj) {
            is String -> "نص"
            is Double -> "رقم"
            is Boolean -> "بولين"
            is List<*> -> "قائمة"
            else -> "عدم"
        }
    }

    private fun isTruthy(obj: Any?): Boolean {
        if (obj == null) return false
        if (obj is Boolean) return obj
        return true
    }
}
