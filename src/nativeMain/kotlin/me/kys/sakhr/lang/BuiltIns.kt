package me.kys.sakhr.lang

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

object BuiltIns {
    val modules = mutableMapOf<String, BuiltInModule>()

    data class BuiltInModule(
        val name: String,
        val functions: List<BuiltInFunction>,
        val extensions: List<BuiltInExtension>
    )

    init {
        // --- Module: التخاطب (IO) ---
        val talkFunctions = mutableListOf<BuiltInFunction>()
        val talkExtensions = mutableListOf<BuiltInExtension>()

        talkFunctions.add(
            BuiltInFunction(
                "أكتب",
                listOf(SakhrType.UNKNOWN),
                SakhrType.VOID
            ) { interpreter, args, _, _ ->
                println(interpreter.stringify(args[0]))
                SakhrUnit
            })

        talkFunctions.add(BuiltInFunction("اقرأ", emptyList(), SakhrType.STRING) { _, _, _, _ ->
            readlnOrNull()
        })

        modules["التخاطب"] = BuiltInModule("التخاطب", talkFunctions, talkExtensions)

        // --- Module: النظام (System) ---
        val systemFunctions = mutableListOf<BuiltInFunction>()
        systemFunctions.add(
            BuiltInFunction(
                "إنهاء_البرنامج",
                listOf(SakhrType.NUMBER),
                SakhrType.VOID
            ) { _, args, _, _ ->
                val code = (args[0] as? Double)?.toInt() ?: 0
                exitProcess(code)
                SakhrUnit
            })
        modules["النظام"] = BuiltInModule("النظام", systemFunctions, emptyList())

        // --- Base Extensions (Auto-loaded or in a base module) ---
        // For now, let's put common extensions in a module called "الأساس"
        val baseExtensions = mutableListOf<BuiltInExtension>()
        val toStrExt: BuiltInExtensionCall =
            { interpreter, _, _, context, _ -> interpreter.stringify(context) }
        baseExtensions.add(
            BuiltInExtension(
                SakhrType.NUMBER,
                "كنص",
                emptyList(),
                SakhrType.STRING,
                toStrExt
            )
        )
        baseExtensions.add(
            BuiltInExtension(
                SakhrType.BOOLEAN,
                "كنص",
                emptyList(),
                SakhrType.STRING,
                toStrExt
            )
        )
        baseExtensions.add(
            BuiltInExtension(
                SakhrType.STRING,
                "كنص",
                emptyList(),
                SakhrType.STRING,
                toStrExt
            )
        )
        baseExtensions.add(
            BuiltInExtension(
                SakhrType.LIST,
                "كنص",
                emptyList(),
                SakhrType.STRING,
                toStrExt
            )
        )

        baseExtensions.add(
            BuiltInExtension(
                SakhrType.STRING,
                "طول",
                emptyList(),
                SakhrType.NUMBER
            ) { _, _, _, context, _ ->
                (context as String).length.toDouble()
            })

        baseExtensions.add(
            BuiltInExtension(
                SakhrType.LIST,
                "حجم",
                emptyList(),
                SakhrType.NUMBER
            ) { _, _, _, context, _ ->
                (context as List<*>).size.toDouble()
            })

        baseExtensions.add(
            BuiltInExtension(
                SakhrType.LIST,
                "أضف",
                listOf(SakhrType.UNKNOWN),
                SakhrType.VOID
            ) { _, args, _, context, _ ->
                @Suppress("UNCHECKED_CAST")
                (context as MutableList<Any?>).add(args[0])
                SakhrUnit
            })

        baseExtensions.add(
            BuiltInExtension(
                SakhrType.LIST,
                "أزل",
                listOf(SakhrType.UNKNOWN),
                SakhrType.VOID
            ) { _, args, _, context, _ ->
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
            })

        baseExtensions.add(
            BuiltInExtension(
                SakhrType.LIST,
                "أدخل",
                listOf(SakhrType.NUMBER, SakhrType.UNKNOWN),
                SakhrType.VOID
            ) { _, args, _, context, location ->
                @Suppress("UNCHECKED_CAST")
                val list = context as MutableList<Any?>
                val index = (args[0] as? Double)?.toInt()
                    ?: throw SakhrError.RuntimeError("يجب أن يكون الفهرس رقماً.", location)
                if (index in 0..list.size) {
                    list.add(index, args[1])
                }
                SakhrUnit
            })

        baseExtensions.add(
            BuiltInExtension(
                SakhrType.LIST,
                "فهرس",
                listOf(SakhrType.UNKNOWN),
                SakhrType.NUMBER
            ) { _, args, _, context, _ ->
                (context as List<Any?>).indexOf(args[0]).toDouble()
            })

        baseExtensions.add(
            BuiltInExtension(
                SakhrType.LIST,
                "استبدل",
                listOf(SakhrType.NUMBER, SakhrType.UNKNOWN),
                SakhrType.VOID
            ) { _, args, _, context, location ->
                @Suppress("UNCHECKED_CAST")
                val list = context as MutableList<Any?>
                val index = (args[0] as? Double)?.toInt()
                    ?: throw SakhrError.RuntimeError("يجب أن يكون الفهرس رقماً.", location)
                if (index in list.indices) {
                    list[index] = args[1]
                } else {
                    throw SakhrError.RuntimeError(
                        "الفهرس ($index) خارج النطاق؛ حجم القائمة هو ${list.size}.",
                        location
                    )
                }
                SakhrUnit
            })

        baseExtensions.add(
            BuiltInExtension(
                SakhrType.LIST,
                "خذ",
                listOf(SakhrType.NUMBER),
                SakhrType.UNKNOWN
            ) { _, args, _, context, location ->
                val list = context as List<Any?>
                val index = (args[0] as? Double)?.toInt()
                    ?: throw SakhrError.RuntimeError("يجب أن يكون الفهرس رقماً.", location)
                if (index in list.indices) {
                    val result = list[index]
                    if (result is SakhrInstance) result.asImmutable() else result
                } else {
                    throw SakhrError.RuntimeError(
                        "الفهرس ($index) خارج النطاق؛ حجم القائمة هو ${list.size}.",
                        location
                    )
                }
            })

        modules["الأساس"] = BuiltInModule("الأساس", emptyList(), baseExtensions)
    }
}
