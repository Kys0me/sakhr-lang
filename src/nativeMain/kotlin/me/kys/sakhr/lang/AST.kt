package me.kys.sakhr.lang

sealed class Expr {
    data class Binary(val left: Expr, val operator: Token, val right: Expr) : Expr()
    data class Grouping(val expression: Expr) : Expr()
    data class Literal(val value: Any?) : Expr()
    data class Variable(val name: Token) : Expr()
    data class Call(val callee: Expr, val paren: Token, val arguments: List<Expr>) : Expr()
    data class Get(val obj: Expr, val name: Token) : Expr()
    data class Context(val keyword: Token) : Expr()
    data class Assignment(val name: Token, val value: Expr) : Expr()
}

sealed class Stmt {
    data class Block(val statements: List<Stmt>) : Stmt()
    data class Expression(val expression: Expr) : Stmt()
    data class Function(
        val name: Token,
        val receiverType: Token?, // For extension methods
        val params: List<Param>,
        val returnType: Token,
        val body: List<Stmt>
    ) : Stmt()
    data class If(val condition: Expr, val thenBranch: Stmt, val elseBranch: Stmt?) : Stmt()
    data class Let(val name: Token, val initializer: Expr?) : Stmt()
    data class Const(val name: Token, val initializer: Expr) : Stmt()
}

data class Param(val name: Token, val type: Token)
