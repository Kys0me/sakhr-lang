package me.kys.sakhr.lang

/**
 * A compile-time optimization pass over the AST:
 * - Constant folding for arithmetic, comparison, logical and unary expressions
 * - Dead-branch elimination for 'إن كان' with constant conditions
 * - Removal of 'ما دام' loops whose condition is constantly false
 * - Unreachable-code removal after 'رد' / 'اكفف' / 'امض' inside a block
 */
class Optimizer {
    fun optimize(statements: List<Stmt>): List<Stmt> = optimizeBlock(statements)

    private fun optimizeBlock(statements: List<Stmt>): List<Stmt> {
        val result = mutableListOf<Stmt>()
        for (stmt in statements) {
            val optimized = optimizeStmt(stmt) ?: continue
            result.add(optimized)
            // Anything after an unconditional jump is unreachable
            if (optimized is Stmt.Return || optimized is Stmt.Break || optimized is Stmt.Continue || optimized is Stmt.Raise) break
        }
        return result
    }

    private fun optimizeStmt(stmt: Stmt): Stmt? {
        return when (stmt) {
            is Stmt.Block -> Stmt.Block(optimizeBlock(stmt.statements))
            is Stmt.Expression -> Stmt.Expression(optimizeExpr(stmt.expression))
            is Stmt.Function -> Stmt.Function(
                stmt.name, stmt.receiverType, stmt.params, stmt.returnType,
                optimizeBlock(stmt.body)
            )
            is Stmt.If -> {
                val condition = optimizeExpr(stmt.condition)
                val thenBranch = optimizeStmt(stmt.thenBranch) ?: Stmt.Block(emptyList())
                val elseBranch = stmt.elseBranch?.let { optimizeStmt(it) }
                if (condition is Expr.Literal) {
                    // The branch is decidable at compile time
                    return if (isTruthy(condition.value)) thenBranch else elseBranch
                }
                Stmt.If(condition, thenBranch, elseBranch)
            }
            is Stmt.While -> {
                val condition = optimizeExpr(stmt.condition)
                // 'ما دام (خطأ)' never runs
                if (condition is Expr.Literal && !isTruthy(condition.value)) return null
                Stmt.While(condition, optimizeStmt(stmt.body) ?: Stmt.Block(emptyList()))
            }
            is Stmt.ForEach -> Stmt.ForEach(
                stmt.indexVar, stmt.elementVar,
                optimizeExpr(stmt.iterable),
                optimizeStmt(stmt.body) ?: Stmt.Block(emptyList())
            )
            is Stmt.Let -> Stmt.Let(stmt.names, stmt.type, stmt.initializer?.let { optimizeExpr(it) })
            is Stmt.Const -> Stmt.Const(stmt.names, stmt.type, optimizeExpr(stmt.initializer))
            is Stmt.Return -> Stmt.Return(stmt.keyword, stmt.value?.let { optimizeExpr(it) })
            is Stmt.Raise -> Stmt.Raise(stmt.keyword, optimizeExpr(stmt.message))
            is Stmt.Break, is Stmt.Continue -> stmt
            is Stmt.Struct -> Stmt.Struct(
                stmt.name,
                stmt.fields.map { Field(it.name, it.type, it.initializer?.let { init -> optimizeExpr(init) }) }
            )
            is Stmt.Enum -> stmt
            is Stmt.Match -> Stmt.Match(
                optimizeExpr(stmt.expression),
                stmt.cases.map { MatchCase(optimizeExpr(it.pattern), optimizeStmt(it.body) ?: Stmt.Block(emptyList())) },
                stmt.defaultBranch?.let { optimizeStmt(it) }
            )
        }
    }

    private fun optimizeExpr(expr: Expr): Expr {
        return when (expr) {
            is Expr.Binary -> {
                val left = optimizeExpr(expr.left)
                val right = optimizeExpr(expr.right)
                foldBinary(left, expr.operator, right) ?: Expr.Binary(left, expr.operator, right)
            }
            is Expr.Logical -> {
                val left = optimizeExpr(expr.left)
                val right = optimizeExpr(expr.right)
                if (left is Expr.Literal) {
                    // Short-circuit is decidable at compile time
                    return when (expr.operator.type) {
                        TokenType.OR -> if (isTruthy(left.value)) left else right
                        else -> if (!isTruthy(left.value)) left else right
                    }
                }
                Expr.Logical(left, expr.operator, right)
            }
            is Expr.Unary -> {
                val right = optimizeExpr(expr.right)
                if (right is Expr.Literal) {
                    when (expr.operator.type) {
                        TokenType.MINUS -> if (right.value is Double) return Expr.Literal(-right.value, expr.operator.location)
                        TokenType.NOT -> return Expr.Literal(!isTruthy(right.value), expr.operator.location)
                        else -> {}
                    }
                }
                Expr.Unary(expr.operator, right)
            }
            is Expr.Grouping -> {
                val inner = optimizeExpr(expr.expression)
                inner as? Expr.Literal ?: Expr.Grouping(inner)
            }
            is Expr.ListLiteral -> Expr.ListLiteral(expr.bracket, expr.elements.map { optimizeExpr(it) })
            is Expr.Index -> Expr.Index(optimizeExpr(expr.obj), expr.bracket, optimizeExpr(expr.index))
            is Expr.Call -> Expr.Call(optimizeExpr(expr.callee), expr.paren, expr.arguments.map { optimizeExpr(it) })
            is Expr.Get -> Expr.Get(optimizeExpr(expr.obj), expr.name)
            is Expr.Set -> Expr.Set(optimizeExpr(expr.obj), expr.name, optimizeExpr(expr.value))
            is Expr.Assignment -> Expr.Assignment(expr.name, optimizeExpr(expr.value))
            is Expr.Literal, is Expr.Variable, is Expr.Context -> expr
        }
    }

    private fun foldBinary(left: Expr, operator: Token, right: Expr): Expr? {
        if (left !is Expr.Literal || right !is Expr.Literal) return null
        val l = left.value
        val r = right.value
        return when (operator.type) {
            TokenType.PLUS -> when (l) {
                is Double if r is Double -> Expr.Literal(l + r, operator.location)
                is String if r is String -> Expr.Literal(l + r, operator.location)
                else -> null
            }
            TokenType.MINUS -> if (l is Double && r is Double) Expr.Literal(l - r, operator.location) else null
            TokenType.STAR -> if (l is Double && r is Double) Expr.Literal(l * r, operator.location) else null
            // Division/modulo by zero is left for the runtime diagnostics
            TokenType.SLASH -> if (l is Double && r is Double && r != 0.0) Expr.Literal(l / r, operator.location) else null
            TokenType.PERCENT -> if (l is Double && r is Double && r != 0.0) Expr.Literal(l % r, operator.location) else null
            TokenType.GREATER -> if (l is Double && r is Double) Expr.Literal(l > r, operator.location) else null
            TokenType.GREATER_EQUALS -> if (l is Double && r is Double) Expr.Literal(l >= r, operator.location) else null
            TokenType.LESS -> if (l is Double && r is Double) Expr.Literal(l < r, operator.location) else null
            TokenType.LESS_EQUALS -> if (l is Double && r is Double) Expr.Literal(l <= r, operator.location) else null
            TokenType.EQUALS_EQUALS -> Expr.Literal(l == r, operator.location)
            TokenType.BANG_EQUALS -> Expr.Literal(l != r, operator.location)
            else -> null
        }
    }

    private fun isTruthy(value: Any?): Boolean {
        if (value == null) return false
        if (value is Boolean) return value
        return true
    }
}
