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
                    println("يجب تحديد مسار الملف المراد تشغيله، مثل: صخر شغل برنامج.صخر")
                } else {
                    runFile(args[1], args.drop(2))
                }
            }
            "فحص" -> {
                if (args.size < 2) {
                    println("يجب تحديد مسار الملف المراد فحصه، مثل: صخر فحص برنامج.صخر")
                } else {
                    checkFile(args[1])
                }
            }
            "تفاعل" -> repl()
            "إصدار" -> println("لغة صخر (Sakhr) - الإصدار الأولي 1.0")
            "مساعدة" -> help()
            else -> {
                val known = listOf("شغل", "فحص", "تفاعل", "إصدار", "مساعدة")
                println("الأمر '${args[0]}' غير معروف.")
                DiagnosticEngine.findClosest(args[0], known)?.let {
                    println("هل قصدت الأمر '$it'؟")
                }
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
        val diagnostics = DiagnosticEngine()
        val resolver = ModuleResolver(diagnostics)
        
        // Try to find project root
        val absolutePath = path // Simplified for now
        val root = resolver.findProjectRoot(absolutePath.substringBeforeLast('/', "."))
        if (root != null) {
            try {
                resolver.loadProjectConfig(root)
            } catch (e: SakhrError.RuntimeError) {
                diagnostics.report(e)
                return
            }
        } else {
            // Requirement: Compilation must fail if the file 'صخر' is missing in the project root.
            // However, for single-file scripts outside a project, we might want to allow it.
            // But the prompt says "Every project must contain a root module file... is required for compilation".
            // I'll enforce it if we're not in REPL.
            println("خطأ: تعذر العثور على ملف تعريف المشروع 'صخر'. كل مشروع صخر يجب أن يحتوي على هذا الملف في جذره.")
            return
        }

        val source = readFile(path) ?: return
        diagnostics.setSource(source, path)
        val lexer = Lexer(source, diagnostics)
        val tokens = lexer.scanTokens()

        if (diagnostics.hasErrors()) return

        val parser = Parser(tokens, diagnostics)
        val statements = parser.parse()

        if (diagnostics.hasErrors()) return

        val entryModule = Module(path.substringAfterLast('/').substringBeforeLast('.'), path, statements)
        
        val typeChecker = TypeChecker(diagnostics, resolver)
        
        resolver.enterModule(entryModule.name)
        typeChecker.check(entryModule)
        resolver.exitModule()

        if (diagnostics.hasErrors()) return

        // Note: Optimizer currently works on List<Stmt>. 
        // We'll optimize the entry module's statements.
        val optimized = Optimizer().optimize(statements)

        val interpreter = Interpreter(diagnostics, resolver)
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
                diagnostics.report(SakhrError.RuntimeError("أُطلق خطأ لم تتم معالجته: ${interpreter.stringify(e.error)}", Location(0, 0)))
            }
        } else {
            println("تعذر بدء البرنامج لأن دالة المطلع 'المطلع' غير موجودة.")
            println("البرنامج يبدأ من دالة اسمها 'المطلع'، عرّفها بـ: إجراء المطلع ابدأ ... انتهى.")
        }
    }

    private fun checkFile(path: String) {
        val diagnostics = DiagnosticEngine()
        val resolver = ModuleResolver(diagnostics)
        
        val root = resolver.findProjectRoot(path.substringBeforeLast('/', "."))
        if (root != null) {
            try {
                resolver.loadProjectConfig(root)
            } catch (e: SakhrError.RuntimeError) {
                diagnostics.report(e)
                return
            }
        } else {
            println("خطأ: تعذر العثور على ملف تعريف المشروع 'صخر'.")
            return
        }

        val source = readFile(path) ?: return
        diagnostics.setSource(source, path)
        val lexer = Lexer(source, diagnostics)
        val tokens = lexer.scanTokens()

        if (diagnostics.hasErrors()) return

        val parser = Parser(tokens, diagnostics)
        val statements = parser.parse()

        if (diagnostics.hasErrors()) {
            println()
            println(diagnostics.summary())
            return
        }

        val entryModule = Module(path.substringAfterLast('/').substringBeforeLast('.'), path, statements)
        val typeChecker = TypeChecker(diagnostics, resolver)
        
        resolver.enterModule(entryModule.name)
        typeChecker.check(entryModule)
        resolver.exitModule()

        if (!diagnostics.hasErrors()) {
            println("تم التحقق بنجاح، ولم يُعثر على أي أخطاء في الكود.")
        } else {
            println()
            println(diagnostics.summary())
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
            println("تعذر فتح الملف '$path'؛ تأكد من وجود الملف وصحة مساره.")
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
