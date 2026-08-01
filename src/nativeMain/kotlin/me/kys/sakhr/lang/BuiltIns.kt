package me.kys.sakhr.lang

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

typealias BuiltInCall = (Interpreter, List<Any?>, Map<String, Any?>, Location) -> Any?
typealias BuiltInExtensionCall = (Interpreter, List<Any?>, Map<String, Any?>, Any?, Location) -> Any?

data class BuiltInFunction(
    val name: String,
    val params: List<SakhrType>,
    val returnType: SakhrType,
    val call: BuiltInCall
)

data class BuiltInExtension(
    val receiverType: SakhrType,
    val name: String,
    val params: List<SakhrType>,
    val returnType: SakhrType,
    val call: BuiltInExtensionCall
)

interface BuiltInModule {
    val name: String
    val functions: List<BuiltInFunction> get() = emptyList()
    val extensions: List<BuiltInExtension> get() = emptyList()
}

object BuiltIns {
    val modules = mutableMapOf<String, BuiltInModule>()

    init {
        listOf(
            IoModule,
            SystemModule,
            MathModule,
            StringModule,
            BaseModule,
            ListModule
        ).forEach { modules[it.name] = it }
    }
}

// --- Module Implementations ---

internal object IoModule : BuiltInModule {
    override val name = "التخاطب"
    override val functions = listOf(
        BuiltInFunction("أكتب", listOf(SakhrType.UNKNOWN), SakhrType.VOID) { interpreter, args, _, _ ->
            println(interpreter.stringify(args[0]))
            SakhrUnit
        },
        BuiltInFunction("اقرأ", emptyList(), SakhrType.STRING) { _, _, _, _ ->
            readlnOrNull()
        }
    )
}

internal object SystemModule : BuiltInModule {
    override val name = "النظام"
    override val functions = listOf(
        BuiltInFunction("إنهاء_البرنامج", listOf(SakhrType.NUMBER), SakhrType.VOID) { _, args, _, _ ->
            val code = (args[0] as? Double)?.toInt() ?: 0
            exitProcess(code)
            SakhrUnit
        }
    )
}

internal object MathModule : BuiltInModule {
    override val name = "الرياضيات"
    override val functions = listOf(
        BuiltInFunction("جيب", listOf(SakhrType.NUMBER), SakhrType.NUMBER) { _, args, _, _ ->
            sin(args[0] as Double)
        },
        BuiltInFunction("جيب_تمام", listOf(SakhrType.NUMBER), SakhrType.NUMBER) { _, args, _, _ ->
            cos(args[0] as Double)
        },
        BuiltInFunction("جذر", listOf(SakhrType.NUMBER), SakhrType.NUMBER) { _, args, _, _ ->
            sqrt(args[0] as Double)
        }
    )
}

internal object StringModule : BuiltInModule {
    override val name = "النصوص"
    override val extensions = listOf(
        BuiltInExtension(SakhrType.STRING, "اعكس", emptyList(), SakhrType.STRING) { _, _, _, context, _ ->
            (context as String).reversed()
        },
        BuiltInExtension(SakhrType.STRING, "اقتطع", listOf(SakhrType.NUMBER, SakhrType.NUMBER), SakhrType.STRING) { _, args, _, context, location ->
            val str = context as String
            val start = (args[0] as Double).toInt()
            val end = (args[1] as Double).toInt()
            if (start < 0 || end > str.length || start > end) {
                throw SakhrError.RuntimeError("نطاق الاقتطع ($start إلى $end) غير صالح لنص طوله ${str.length}.", location)
            }
            str.substring(start, end)
        }
    )
}

internal object BaseModule : BuiltInModule {
    override val name = "الأساس"
    private val toStrExt: BuiltInExtensionCall = { interpreter, _, _, context, _ -> interpreter.stringify(context) }
    
    override val extensions = listOf(
        BuiltInExtension(SakhrType.NUMBER, "كنص", emptyList(), SakhrType.STRING, toStrExt),
        BuiltInExtension(SakhrType.BOOLEAN, "كنص", emptyList(), SakhrType.STRING, toStrExt),
        BuiltInExtension(SakhrType.STRING, "كنص", emptyList(), SakhrType.STRING, toStrExt),
        BuiltInExtension(SakhrType.LIST, "كنص", emptyList(), SakhrType.STRING, toStrExt),
        BuiltInExtension(SakhrType.UNKNOWN, "كنص", emptyList(), SakhrType.STRING, toStrExt),
        BuiltInExtension(SakhrType.NULL_LITERAL, "كنص", emptyList(), SakhrType.STRING, toStrExt),
        
        BuiltInExtension(SakhrType.STRING, "طول", emptyList(), SakhrType.NUMBER) { _, _, _, context, _ ->
            (context as String).length.toDouble()
        },
        
        BuiltInExtension(SakhrType.LIST, "حجم", emptyList(), SakhrType.NUMBER) { _, _, _, context, _ ->
            (context as List<*>).size.toDouble()
        },
        
        BuiltInExtension(SakhrType.LIST, "أضف", listOf(SakhrType.UNKNOWN), SakhrType.VOID) { _, args, _, context, _ ->
            @Suppress("UNCHECKED_CAST")
            (context as MutableList<Any?>).add(args[0])
            SakhrUnit
        },
        
        BuiltInExtension(SakhrType.LIST, "أزل", listOf(SakhrType.UNKNOWN), SakhrType.VOID) { _, args, _, context, _ ->
            @Suppress("UNCHECKED_CAST")
            val list = context as MutableList<Any?>
            val arg = args[0]
            if (arg is Double) {
                val index = arg.toInt()
                if (index in list.indices) list.removeAt(index)
            } else {
                list.remove(arg)
            }
            SakhrUnit
        },
        
        BuiltInExtension(SakhrType.LIST, "أدخل", listOf(SakhrType.NUMBER, SakhrType.UNKNOWN), SakhrType.VOID) { _, args, _, context, location ->
            @Suppress("UNCHECKED_CAST")
            val list = context as MutableList<Any?>
            val index = (args[0] as Double).toInt()
            if (index in 0..list.size) list.add(index, args[1])
            else throw SakhrError.RuntimeError("الفهرس ($index) خارج النطاق؛ حجم القائمة هو ${list.size}.", location)
            SakhrUnit
        },
        
        BuiltInExtension(SakhrType.LIST, "فهرس", listOf(SakhrType.UNKNOWN), SakhrType.NUMBER) { _, args, _, context, _ ->
            (context as List<Any?>).indexOf(args[0]).toDouble()
        },
        
        BuiltInExtension(SakhrType.LIST, "استبدل", listOf(SakhrType.NUMBER, SakhrType.UNKNOWN), SakhrType.VOID) { _, args, _, context, location ->
            @Suppress("UNCHECKED_CAST")
            val list = context as MutableList<Any?>
            val index = (args[0] as Double).toInt()
            if (index in list.indices) list[index] = args[1]
            else throw SakhrError.RuntimeError("الفهرس ($index) خارج النطاق؛ حجم القائمة هو ${list.size}.", location)
            SakhrUnit
        },
        
        BuiltInExtension(SakhrType.LIST, "خذ", listOf(SakhrType.NUMBER), SakhrType.UNKNOWN) { _, args, _, context, location ->
            val list = context as List<Any?>
            val index = (args[0] as Double).toInt()
            if (index in list.indices) {
                val result = list[index]
                if (result is SakhrInstance) result.asImmutable() else result
            } else {
                throw SakhrError.RuntimeError("الفهرس ($index) خارج النطاق؛ حجم القائمة هو ${list.size}.", location)
            }
        }
    )
}

internal object ListModule : BuiltInModule {
    override val name = "القوائم"
    override val extensions = listOf(
        BuiltInExtension(SakhrType.LIST, "حول", listOf(SakhrType.fromLexeme("(مجهول) => مجهول")), SakhrType.LIST) { interpreter, args, _, context, location ->
            val list = context as List<Any?>
            val callback = args[0] as? SakhrCallable ?: throw SakhrError.RuntimeError("يجب تمرير دالة لـ 'حول'.", location)
            list.map { item ->
                callback.call(interpreter, listOf(item), emptyMap(), location)
            }.toMutableList()
        },
        
        BuiltInExtension(SakhrType.LIST, "صف", listOf(SakhrType.fromLexeme("(مجهول) => منطقي")), SakhrType.LIST) { interpreter, args, _, context, location ->
            val list = context as List<Any?>
            val callback = args[0] as? SakhrCallable ?: throw SakhrError.RuntimeError("يجب تمرير دالة لـ 'صف'.", location)
            list.filter { item ->
                interpreter.isTruthy(callback.call(interpreter, listOf(item), emptyMap(), location))
            }.toMutableList()
        },
        
        BuiltInExtension(SakhrType.LIST, "اختزل", listOf(SakhrType.UNKNOWN, SakhrType.fromLexeme("(مجهول، مجهول) => مجهول")), SakhrType.UNKNOWN) { interpreter, args, _, context, location ->
            val list = context as List<Any?>
            val initial = args[0]
            val callback = args[1] as? SakhrCallable ?: throw SakhrError.RuntimeError("يجب تمرير دالة لـ 'اختزل'.", location)
            list.fold(initial) { acc, item ->
                callback.call(interpreter, listOf(acc, item), emptyMap(), location)
            }
        },
        
        BuiltInExtension(SakhrType.LIST, "لكل_عنصر", listOf(SakhrType.fromLexeme("(مجهول) => عدم")), SakhrType.VOID) { interpreter, args, _, context, location ->
            val list = context as List<Any?>
            val callback = args[0] as? SakhrCallable ?: throw SakhrError.RuntimeError("يجب تمرير دالة لـ 'لكل_عنصر'.", location)
            list.forEach { item ->
                callback.call(interpreter, listOf(item), emptyMap(), location)
            }
            SakhrUnit
        },
        
        BuiltInExtension(SakhrType.LIST, "دمج", listOf(SakhrType.STRING), SakhrType.STRING) { interpreter, args, _, context, _ ->
            val list = context as List<Any?>
            val separator = args[0] as String
            list.joinToString(separator) { interpreter.stringify(it) }
        },
        
        BuiltInExtension(SakhrType.LIST, "خذ_أو_فارغ", listOf(SakhrType.NUMBER), SakhrType.fromLexeme("مجهول؟")) { _, args, _, context, _ ->
            val list = context as List<Any?>
            val index = (args[0] as Double).toInt()
            if (index in list.indices) list[index] else null
        },
        
        BuiltInExtension(SakhrType.LIST, "محتوى", listOf(SakhrType.NUMBER), SakhrType.fromLexeme("مجهول؟")) { _, args, _, context, _ ->
            val list = context as List<Any?>
            val index = (args[0] as Double).toInt()
            if (index in list.indices) {
                SakhrResult(list[index], null)
            } else {
                SakhrResult(null, "الفهرس ($index) خارج النطاق")
            }
        }
    )
}
