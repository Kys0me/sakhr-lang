package me.kys.sakhr.lang

import kotlin.test.Test
import kotlin.test.assertTrue

class LambdaTest {

    private fun runSource(source: String): List<SakhrError> {
        val wrappedSource = "إجراء المطلع() ابدأ\n$source\nانتهى"
        val diagnostics = DiagnosticEngine()
        diagnostics.setSource(wrappedSource)
        val lexer = Lexer(wrappedSource, diagnostics)
        val tokens = lexer.scanTokens()
        if (diagnostics.hasErrors()) return diagnostics.errors

        val parser = Parser(tokens, diagnostics)
        val statements = parser.parse()
        if (diagnostics.hasErrors()) return diagnostics.errors

        val typeChecker = TypeChecker(diagnostics)
        typeChecker.check(statements)
        if (diagnostics.hasErrors()) return diagnostics.errors

        val interpreter = Interpreter(diagnostics)
        interpreter.execute(statements)
        return diagnostics.errors
    }

    private fun runTopLevelSource(source: String): List<SakhrError> {
        val diagnostics = DiagnosticEngine()
        diagnostics.setSource(source)
        val lexer = Lexer(source, diagnostics)
        val tokens = lexer.scanTokens()
        if (diagnostics.hasErrors()) return diagnostics.errors

        val parser = Parser(tokens, diagnostics)
        val statements = parser.parse()
        if (diagnostics.hasErrors()) return diagnostics.errors

        val typeChecker = TypeChecker(diagnostics)
        typeChecker.check(statements)
        if (diagnostics.hasErrors()) return diagnostics.errors

        val interpreter = Interpreter(diagnostics)
        interpreter.execute(statements)
        return diagnostics.errors
    }

    @Test
    fun testShortLambda() {
        val source = """
            ليكن مضاعف = دالة(س) => س * 2
            أكتب(مضاعف(5))
        """.trimIndent()
        val errors = runSource(source)
        assertTrue(errors.isEmpty(), "Should have no errors: ${errors.map { it.message }}")
    }

    @Test
    fun testLongLambda() {
        val source = """
            ليكن مضاعف = دالة(س) ابدأ
                ليكن الناتج = س * 2
                رد الناتج
            انتهى
            أكتب(مضاعف(10))
        """.trimIndent()
        val errors = runSource(source)
        assertTrue(errors.isEmpty(), "Should have no errors: ${errors.map { it.message }}")
    }

    @Test
    fun testLambdaTypeAnnotation() {
        val source = """
            ليكن مضاعف: (رقم) => رقم = دالة(س) => س * 2
            أكتب(مضاعف(15))
        """.trimIndent()
        val errors = runSource(source)
        assertTrue(errors.isEmpty(), "Should have no errors: ${errors.map { it.message }}")
    }

    @Test
    fun testHigherOrderFunction() {
        val source = """
            ليكن الأرقام = [1، 2، 3]
            ليكن المربعات = الأرقام.حول(دالة(س) => س * س)
            أكتب(المربعات)
        """.trimIndent()
        val errors = runSource(source)
        assertTrue(errors.isEmpty(), "Should have no errors: ${errors.map { it.message }}")
    }

    @Test
    fun testFilterNaming() {
        val source = """
            ليكن الأرقام = [1، 10، 20]
            ليكن الكبيرة = الأرقام.صف(دالة(س) => س > 5)
            أكتب(الكبيرة)
        """.trimIndent()
        val errors = runSource(source)
        assertTrue(errors.isEmpty(), "Should have no errors: ${errors.map { it.message }}")
    }
    
    @Test
    fun testReduce() {
        val source = """
            ليكن الأرقام = [1، 2، 3، 4]
            ليكن المجموع = الأرقام.اختزل(0، دالة(أ، ب) => أ + ب)
            أكتب(المجموع)
        """.trimIndent()
        val errors = runSource(source)
        assertTrue(errors.isEmpty(), "Should have no errors: ${errors.map { it.message }}")
    }

    @Test
    fun testInvalidReturnInShortLambda() {
        val source = "ليكن خطأ = دالة(س) => رد س * 2"
        val wrappedSource = "إجراء المطلع() ابدأ\n$source\nانتهى"
        val diagnostics = DiagnosticEngine()
        val lexer = Lexer(wrappedSource, diagnostics)
        val tokens = lexer.scanTokens()
        val parser = Parser(tokens, diagnostics)
        try {
            parser.parse()
        } catch (_: Exception) {}
        assertTrue(diagnostics.hasErrors(), "Should have a syntax error")
    }

    @Test
    fun testVoidLambda() {
        val source = """
            ليكن طباعة: (نص) => عدم = دالة(س) ابدأ
                أكتب(س)
            انتهى
            طباعة("أهلاً")
        """.trimIndent()
        val errors = runSource(source)
        assertTrue(errors.isEmpty(), "Should have no errors: ${errors.map { it.message }}")
    }

    @Test
    fun testStructInLambda() {
        val source = """
            بنية مستخدم ابدأ
                الاسم: نص
            انتهى
            إجراء المطلع() ابدأ
                ليكن معالج: (مستخدم) => نص = دالة(م) => "المستخدم: " + م.الاسم
                ليكن م = مستخدم(الاسم = "زيد")
                أكتب(معالج(م))
            انتهى
        """.trimIndent()
        val errors = runTopLevelSource(source)
        assertTrue(errors.isEmpty(), "Should have no errors: ${errors.map { it.message }}")
    }

    @Test
    fun testEnumInLambda() {
        val source = """
            تعداد حالة ابدأ
                متصل
                غير_متصل
            انتهى
            إجراء المطلع() ابدأ
                ليكن محول: (حالة) => نص = دالة(ح) ابدأ
                    طابق ح
                        حالة.متصل إذن رد "أونلاين"
                        حالة.غير_متصل إذن رد "أوفلاين"
                        وإلا رد "غير معروف"
                    انتهى
                انتهى
                أكتب(محول(حالة.متصل))
            انتهى
        """.trimIndent()
        val errors = runTopLevelSource(source)
        assertTrue(errors.isEmpty(), "Should have no errors: ${errors.map { it.message }}")
    }
}
