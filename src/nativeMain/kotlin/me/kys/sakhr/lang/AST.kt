package me.kys.sakhr.lang

sealed class Expr {
    data class Binary(val left: Expr, val operator: Token, val right: Expr) : Expr()
    data class Logical(val left: Expr, val operator: Token, val right: Expr) : Expr()
    data class Unary(val operator: Token, val right: Expr) : Expr()
    data class Grouping(val expression: Expr) : Expr()
    data class Literal(val value: Any?) : Expr()
    data class ListLiteral(val bracket: Token, val elements: List<Expr>) : Expr()
    data class Variable(val name: Token) : Expr()
    data class Call(val callee: Expr, val paren: Token, val arguments: List<Expr>) : Expr()
    data class Get(val obj: Expr, val name: Token) : Expr()
    data class Index(val obj: Expr, val bracket: Token, val index: Expr) : Expr()
    data class Set(val obj: Expr, val name: Token, val value: Expr) : Expr()
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
        val returnType: Token?,
        val body: List<Stmt>
    ) : Stmt()
    data class If(val condition: Expr, val thenBranch: Stmt, val elseBranch: Stmt?) : Stmt()
    data class While(val condition: Expr, val body: Stmt) : Stmt()
    data class ForEach(
        val indexVar: Token?,
        val elementVar: Token,
        val iterable: Expr,
        val body: Stmt
    ) : Stmt()
    data class Break(val keyword: Token) : Stmt()
    data class Continue(val keyword: Token) : Stmt()
    data class Let(val name: Token, val type: Token?, val initializer: Expr?) : Stmt()
    data class Const(val name: Token, val type: Token?, val initializer: Expr) : Stmt()
    data class Return(val keyword: Token, val value: Expr?) : Stmt()
    data class Struct(
        val name: Token,
        val fields: List<Field>
    ) : Stmt()
}

data class Param(val name: Token, val type: Token?)
data class Field(val name: Token, val type: Token?, val initializer: Expr?)
