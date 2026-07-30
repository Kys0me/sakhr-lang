package me.kys.sakhr.lang

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

class Cli {
    fun run(args: Array<String>) {
        if (args.isEmpty()) {
            help()
            return
        }

        when (args[0]) {
            "شغل" -> {
                if (args.size < 2) {
                    println("خطأ: يجب تحديد مسار الملف.")
                } else {
                    runFile(args[1], args.drop(2))
                }
            }
            "فحص" -> {
                if (args.size < 2) {
                    println("خطأ: يجب تحديد مسار الملف.")
                } else {
                    checkFile(args[1])
                }
            }
            "تفاعل" -> repl()
            "إصدار" -> println("لغة صخر (Sakhr) - الإصدار الأولي 1.0")
            "مساعدة" -> help()
            else -> {
                println("أمر غير معروف: ${args[0]}")
                help()
            }
        }
    }

    private fun help() {
        println("""
لغة صخر - مترجم ومفسر اللغة العربية

الاستخدام:
  صخر <الأمر> [خيارات]

الأوامر:
  شغل <ملف> [وسائط]    تنفيذ ملف برمجي
  فحص <ملف>            التحقق من صحة الكود في ملف دون تنفيذه
  تفاعل                    بدء جلسة تفاعلية
  إصدار                عرض معلومات الإصدار
  مساعدة               عرض دليل المساعدة هذا
        """.trimIndent())
    }

    private fun runFile(path: String, args: List<String>) {
        val source = readFile(path) ?: return
        val diagnostics = DiagnosticEngine()
        diagnostics.setSource(source, path)
        val lexer = Lexer(source, diagnostics)
        val tokens = lexer.scanTokens()

        if (diagnostics.hasErrors()) return

        val parser = Parser(tokens, diagnostics)
        val statements = parser.parse()

        if (diagnostics.hasErrors()) return

        val typeChecker = TypeChecker(diagnostics)
        typeChecker.check(statements)

        if (diagnostics.hasErrors()) return

        val optimized = Optimizer().optimize(statements)

        val interpreter = Interpreter(diagnostics)
        interpreter.execute(optimized)

        val mainFunc = interpreter.globals.getRaw("المطلع")
        if (mainFunc is SakhrCallable) {
            try {
                val result = mainFunc.call(interpreter, listOf(args), location = Location(0, 0))
                if (result != null && result != SakhrUnit) {
                    println(interpreter.stringify(result))
                }
            } catch (e: SakhrError.RuntimeError) {
                diagnostics.report(e)
            } catch (e: SakhrRaiseException) {
                diagnostics.report(SakhrError.RuntimeError("خطأ غير معالج: ${interpreter.stringify(e.error)}", Location(0, 0)))
            }
        } else {
            println("خطأ: تعذر العثور على دالة 'المطلع' لبدء البرنامج.")
        }
    }

    private fun checkFile(path: String) {
        val source = readFile(path) ?: return
        val diagnostics = DiagnosticEngine()
        diagnostics.setSource(source, path)
        val lexer = Lexer(source, diagnostics)
        val tokens = lexer.scanTokens()

        if (diagnostics.hasErrors()) return

        val parser = Parser(tokens, diagnostics)
        val statements = parser.parse()

        if (diagnostics.hasErrors()) return

        val typeChecker = TypeChecker(diagnostics)
        typeChecker.check(statements)

        if (!diagnostics.hasErrors()) {
            println("تم التحقق بنجاح: الكود سليم.")
        }
    }

    private fun repl() {
        println("لغة صخر (تفاعل) - اكتب 'خروج' أو اضغط Ctrl+D للخروج.")
        val diagnostics = DiagnosticEngine()
        val typeChecker = TypeChecker(diagnostics)
        val interpreter = Interpreter(diagnostics)

        while (true) {
            print("> ")
            val line = readlnOrNull() ?: break
            if (line == "خروج") break
            if (line.isBlank()) continue

            diagnostics.clear()
            executeCommand(line, diagnostics, typeChecker, interpreter)
        }
    }

    private fun executeCommand(source: String, diagnostics: DiagnosticEngine, typeChecker: TypeChecker, interpreter: Interpreter) {
        diagnostics.setSource(source)
        val lexer = Lexer(source, diagnostics)
        val tokens = lexer.scanTokens()
        if (diagnostics.hasErrors()) return

        val parser = Parser(tokens, diagnostics)
        val statements = parser.parse()
        if (diagnostics.hasErrors()) return

        typeChecker.check(statements)
        if (diagnostics.hasErrors()) return

        interpreter.execute(Optimizer().optimize(statements))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readFile(path: String): String? {
        val file = fopen(path, "r") ?: run {
            println("خطأ: تعذر فتح الملف '$path'.")
            return null
        }

        try {
            val sb = StringBuilder()
            val buffer = ByteArray(4096)
            buffer.usePinned { pinned ->
                while (true) {
                    val length = fread(pinned.addressOf(0), 1.convert(), buffer.size.convert(), file).toInt()
                    if (length <= 0) break
                    sb.append(buffer.decodeToString(0, length))
                }
            }
            return sb.toString()
        } finally {
            fclose(file)
        }
    }
}
