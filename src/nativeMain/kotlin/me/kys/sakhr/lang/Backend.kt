package me.kys.sakhr.lang

interface Backend {
    fun execute(statements: List<Stmt>)
}
