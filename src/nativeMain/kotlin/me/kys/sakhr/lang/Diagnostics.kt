package me.kys.sakhr.lang

data class Location(val line: Int, val column: Int)

sealed class SakhrError(val msg: String, val location: Location) : RuntimeException(msg) {
    class LexicalError(message: String, location: Location) : SakhrError("خطأ معجمي: $message", location)
    class SyntaxError(message: String, location: Location) : SakhrError("خطأ نحوي: $message", location)
    class TypeError(message: String, location: Location) : SakhrError("خطأ دلالي: $message", location)
    class RuntimeError(message: String, location: Location) : SakhrError("خطأ تنفيذي: $message", location)
}

class DiagnosticEngine {
    private val errors = mutableListOf<SakhrError>()

    fun report(error: SakhrError) {
        errors.add(error)
        println("${error.message} [السطر ${error.location.line}، العمود ${error.location.column}]")
    }

    fun hasErrors() = errors.isNotEmpty()
}
