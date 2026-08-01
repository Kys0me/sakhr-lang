package me.kys.sakhr.lang

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.opendir

class ModuleResolver(
    private val diagnostics: DiagnosticEngine,
    private val executablePath: String? = null
) {
    private val moduleCache = mutableMapOf<String, Module>()
    private val loadingStack = mutableListOf<String>()
    
    var projectRoot: String? = null
        private set

    fun findProjectRoot(startPath: String): String? {
        var current = startPath
        while (current.isNotEmpty() && current != "/") {
            val configPath = "$current/صخر"
            if (fileExists(configPath)) {
                projectRoot = current
                return current
            }
            current = current.substringBeforeLast('/', "")
        }
        return null
    }

    fun loadProjectConfig(root: String): ProjectConfig {
        val content = readFile("$root/صخر") ?: throw SakhrError.RuntimeError("تعذر قراءة ملف المشروع 'صخر'.", Location(0, 0))
        val lines = content.lines()
        var name = "مشروعي"
        var version = "1.0.0"

        for (line in lines) {
            if (line.isBlank()) continue
            val parts = line.split("=")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().removeSurrounding("\"")
                when (key) {
                    "الاسم" -> name = value
                    "الإصدار" -> version = value
                }
            }
        }
        return ProjectConfig(name, version, root)
    }

    fun enterModule(name: String) {
        loadingStack.add(name)
    }

    fun exitModule() {
        if (loadingStack.isNotEmpty()) {
            loadingStack.removeAt(loadingStack.size - 1)
        }
    }

    fun isResolving(name: String): Boolean = loadingStack.contains(name)

    fun getLoadingStack(): List<String> = loadingStack.toList()

    fun registerModule(module: Module) {
        moduleCache[module.name] = module
    }

    fun resolve(import: Stmt.Import): Module? {
        val pathString = import.path.joinToString(".") { it.lexeme }
        val isStdLib = import.isStdLib
        
        val key = if (isStdLib) "الأم.$pathString" else pathString
        if (moduleCache.containsKey(key)) return moduleCache[key]

        if (loadingStack.contains(key)) {
            val chain = (loadingStack + key).joinToString(" -> ")
            diagnostics.report(
                SakhrError.TypeError(
                    "تم اكتشاف حلقة استجلاب دائرية: $chain",
                    import.path.first().location,
                    suggestion = "قم بإزالة الاستجلاب الدائري لكسر الحلقة."
                )
            )
            return null
        }

        val module = loadModule(import)

        if (module != null) {
            moduleCache[key] = module
        }
        return module
    }

    private fun loadModule(import: Stmt.Import): Module? {
        val pathString = import.path.joinToString(".") { it.lexeme }
        val isStdLib = import.isStdLib
        val key = if (isStdLib) "الأم.$pathString" else pathString
        
        loadingStack.add(key)
        val result = tryLoadModule(import)
        loadingStack.removeAt(loadingStack.size - 1)
        return result
    }

    private fun tryLoadModule(import: Stmt.Import): Module? {
        val filePath = getFilePath(import) ?: return null
        val source = readFile(filePath) ?: run {
            diagnostics.report(
                SakhrError.TypeError(
                    "تعذر العثور على الوحدة '${import.path.joinToString(".") { it.lexeme }}' في المسار: $filePath",
                    import.path.first().location
                )
            )
            return null
        }

        val tempDiagnostics = DiagnosticEngine()
        tempDiagnostics.setSource(source, filePath)
        
        val lexer = Lexer(source, tempDiagnostics)
        val tokens = lexer.scanTokens()
        if (tempDiagnostics.hasErrors()) {
            mergeDiagnostics(tempDiagnostics)
            return null
        }

        val parser = Parser(tokens, tempDiagnostics)
        val statements = parser.parse()
        if (tempDiagnostics.hasErrors()) {
            mergeDiagnostics(tempDiagnostics)
            return null
        }

        return Module(
            name = import.path.last().lexeme,
            path = filePath,
            statements = statements,
            isStdLib = import.isStdLib
        )
    }

    private fun getFilePath(import: Stmt.Import): String? {
        val path = import.path.map { it.lexeme }
        return if (import.isStdLib) {
            val stdLibDir = getStdLibPath() ?: return null
            "$stdLibDir/${path.joinToString("/")}.صخر"
        } else {
            val root = projectRoot ?: "."
            "$root/${path.joinToString("/")}.صخر"
        }
    }

    private fun getStdLibPath(): String? {
        // If executablePath is known, look for "الأم" sibling
        val exeDir = executablePath?.substringBeforeLast('/', ".") ?: "."
        val stdPath = "$exeDir/الأم"
        if (dirExists(stdPath)) return stdPath
        
        // Fallback for development if not found relative to exe
        if (dirExists("الأم")) return "الأم"
        
        diagnostics.report(SakhrError.RuntimeError("تعذر العثور على المجلد 'الأم' (المكتبة القياسية).", Location(0, 0)))
        return null
    }

    private fun mergeDiagnostics(other: DiagnosticEngine) {
        other.errors.forEach { diagnostics.report(it) }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun fileExists(path: String): Boolean {
        val file = fopen(path, "r")
        return if (file != null) {
            fclose(file)
            true
        } else false
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun dirExists(path: String): Boolean {
        val dir = opendir(path)
        return if (dir != null) {
            closedir(dir)
            true
        } else false
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readFile(path: String): String? {
        val file = fopen(path, "r") ?: return null
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
