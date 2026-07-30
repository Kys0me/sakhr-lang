package me.kys.sakhr.lang

import kotlin.system.exitProcess

typealias BuiltInCall = (Interpreter, List<Any?>, Map<String, Any?>) -> Any?
typealias BuiltInExtensionCall = (Interpreter, List<Any?>, Map<String, Any?>, Any?) -> Any?

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

object BuiltIns {
    val functions = mutableListOf<BuiltInFunction>()
    val extensions = mutableListOf<BuiltInExtension>()

    private fun function(name: String, params: List<SakhrType>, returnType: SakhrType, call: BuiltInCall) {
        functions.add(BuiltInFunction(name, params, returnType, call))
    }

    private fun extension(receiverType: SakhrType, name: String, params: List<SakhrType>, returnType: SakhrType, call: BuiltInExtensionCall) {
        extensions.add(BuiltInExtension(receiverType, name, params, returnType, call))
    }

    init {
        // --- Standard Functions ---

        val printCall: BuiltInCall = { interpreter, args, _ ->
            println(interpreter.stringify(args[0]))
            SakhrUnit
        }

        function("أكتب", listOf(SakhrType.UNKNOWN), SakhrType.VOID, printCall)

        function("إنهاء_البرنامج", listOf(SakhrType.NUMBER), SakhrType.VOID) { _, args, _ ->
            val code = (args[0] as? Double)?.toInt() ?: 0
            exitProcess(code)
            SakhrUnit
        }

        function("اقرأ", emptyList(), SakhrType.STRING) { _, _, _ ->
            readlnOrNull()
        }

        // --- Extension Methods ---

        val toStrExt: BuiltInExtensionCall = { interpreter, _, _, context -> interpreter.stringify(context) }
        extension(SakhrType.NUMBER, "كنص", emptyList(), SakhrType.STRING, toStrExt)
        extension(SakhrType.BOOLEAN, "كنص", emptyList(), SakhrType.STRING, toStrExt)
        extension(SakhrType.STRING, "كنص", emptyList(), SakhrType.STRING, toStrExt)
        extension(SakhrType.LIST, "كنص", emptyList(), SakhrType.STRING, toStrExt)

        extension(SakhrType.STRING, "طول", emptyList(), SakhrType.NUMBER) { _, _, _, context ->
            (context as String).length.toDouble()
        }

        extension(SakhrType.LIST, "حجم", emptyList(), SakhrType.NUMBER) { _, _, _, context ->
            (context as List<*>).size.toDouble()
        }

        extension(SakhrType.LIST, "أضف", listOf(SakhrType.UNKNOWN), SakhrType.VOID) { _, args, _, context ->
            @Suppress("UNCHECKED_CAST")
            (context as MutableList<Any?>).add(args[0])
            SakhrUnit
        }

        extension(SakhrType.LIST, "أزل", listOf(SakhrType.UNKNOWN), SakhrType.VOID) { _, args, _, context ->
            @Suppress("UNCHECKED_CAST")
            val list = context as MutableList<Any?>
            val arg = args[0]
            if (arg is Double) {
                val index = arg.toInt()
                if (index in list.indices) {
                    list.removeAt(index)
                }
            } else {
                list.remove(arg)
            }
            SakhrUnit
        }

        extension(SakhrType.LIST, "أدخل", listOf(SakhrType.NUMBER, SakhrType.UNKNOWN), SakhrType.VOID) { _, args, _, context ->
            @Suppress("UNCHECKED_CAST")
            val list = context as MutableList<Any?>
            val index = (args[0] as? Double)?.toInt() ?: throw SakhrError.RuntimeError("يجب أن يكون الفهرس رقماً.", Location(0, 0))
            if (index in 0..list.size) {
                list.add(index, args[1])
            }
            SakhrUnit
        }

        extension(SakhrType.LIST, "فهرس", listOf(SakhrType.UNKNOWN), SakhrType.NUMBER) { _, args, _, context ->
            (context as List<Any?>).indexOf(args[0]).toDouble()
        }

        extension(SakhrType.LIST, "استبدل", listOf(SakhrType.NUMBER, SakhrType.UNKNOWN), SakhrType.VOID) { _, args, _, context ->
            @Suppress("UNCHECKED_CAST")
            val list = context as MutableList<Any?>
            val index = (args[0] as? Double)?.toInt() ?: throw SakhrError.RuntimeError("يجب أن يكون الفهرس رقماً.", Location(0, 0))
            if (index in list.indices) {
                list[index] = args[1]
            } else {
                throw SakhrError.RuntimeError("الفهرس ($index) خارج النطاق؛ حجم القائمة هو ${list.size}.", Location(0, 0))
            }
            SakhrUnit
        }

        extension(SakhrType.LIST, "خذ", listOf(SakhrType.NUMBER), SakhrType.UNKNOWN) { _, args, _, context ->
            val list = context as List<Any?>
            val index = (args[0] as? Double)?.toInt() ?: throw SakhrError.RuntimeError("يجب أن يكون الفهرس رقماً.", Location(0, 0))
            if (index in list.indices) {
                val result = list[index]
                if (result is SakhrInstance) result.asImmutable() else result
            } else {
                throw SakhrError.RuntimeError("الفهرس ($index) خارج النطاق؛ حجم القائمة هو ${list.size}.", Location(0, 0))
            }
        }
    }
}
