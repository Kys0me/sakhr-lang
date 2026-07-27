package me.kys.sakhr.lang

fun main(args: Array<String>) {
    val source = """
        إجراء المطلع(الوسائط: قائمة نص): عدم ابدأ
            ألزم الاسم = "صخر"
            أكتب("مرحباً بك في لغة " + الاسم)
            
            ليكن العداد = 1
            إن كان (العداد < 2) إذن
                أكتب("العداد أصغر من 2")
            وإلا
                أكتب("العداد أكبر من أو يساوي 2")
            انتهى
            
            الوسائط.أكتب_كلها()
        انتهى

        إجراء قائمة::أكتب_كلها(): عدم ابدأ
            ليكن ل = السياق
            أكتب("عدد العناصر: " + ل.حجم)
            أكتب("العنصر الأول: " + ل.خذ(0))
        انتهى
    """.trimIndent()

    val diagnostics = DiagnosticEngine()
    val lexer = Lexer(source, diagnostics)
    val tokens = lexer.scanTokens()

    if (!diagnostics.hasErrors()) {
        val parser = Parser(tokens, diagnostics)
        val statements = parser.parse()

        if (!diagnostics.hasErrors()) {
            val typeChecker = TypeChecker(diagnostics)
            typeChecker.check(statements)

            if (!diagnostics.hasErrors()) {
                val interpreter = Interpreter(diagnostics)
                interpreter.execute(statements)

                val mainFunc = interpreter.globals.getRaw("المطلع")
                if (mainFunc is SakhrCallable) {
                    try {
                        mainFunc.call(interpreter, listOf(args.toList()))
                    } catch (e: SakhrError.RuntimeError) {
                        diagnostics.report(e)
                    }
                }
            }
        }
    }

    // Test Immutability violation
    println("\nاختبار مخالفة (ألزم):")
    val immutabilityDiagnostics = DiagnosticEngine()
    val immutabilitySource = """
        ألزم ثابت = 1
        ثابت = 2
    """.trimIndent()
    val immTokens = Lexer(immutabilitySource, immutabilityDiagnostics).scanTokens()
    val immStmts = Parser(immTokens, immutabilityDiagnostics).parse()
    if (!immutabilityDiagnostics.hasErrors()) {
        TypeChecker(immutabilityDiagnostics).check(immStmts)
        if (!immutabilityDiagnostics.hasErrors()) {
            Interpreter(immutabilityDiagnostics).execute(immStmts)
        }
    }

    // Test decimal formatting and restrictions
    println("\nاختبار تنسيق الأرقام والقيود:")
    val decimalSource = """
        أكتب(1.5)
        أكتب(3٫14) // Should fail lexical check now
    """.trimIndent()
    val decTokens = Lexer(decimalSource, diagnostics).scanTokens()
    
    // Test naming rules
    println("\nاختبار قواعد التسمية (استخدام _):")
    val namingSource = "ليكن العداد_الأول = 10"
    val namingTokens = Lexer(namingSource, DiagnosticEngine()).scanTokens()
    println("رموز التسمية: " + namingTokens.filter { it.type != TokenType.EOF }.map { "${it.type}(${it.lexeme})" })
}
