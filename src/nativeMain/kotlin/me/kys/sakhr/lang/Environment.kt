package me.kys.sakhr.lang

class Environment(private val enclosing: Environment? = null) {
    private val values = mutableMapOf<String, Any?>()
    private val constants = mutableSetOf<String>()

    fun define(name: String, value: Any?, isConstant: Boolean) {
        values[name] = value
        if (isConstant) constants.add(name)
    }

    fun get(name: Token): Any? {
        var env: Environment? = this
        while (env != null) {
            val values = env.values
            if (values.containsKey(name.lexeme)) return values[name.lexeme]
            env = env.enclosing
        }
        throw SakhrError.RuntimeError(
            "المتغير '${name.lexeme}' غير معرّف في هذا النطاق.",
            name.location,
            DiagnosticEngine.findClosest(name.lexeme, visibleNames())?.let { "هل قصدت '$it'؟" }
                ?: "عرّفه أولاً باستخدام 'ليكن ${name.lexeme} = ...'.",
            length = name.lexeme.length
        )
    }

    /** Collects the names visible from this scope outward, for suggestions. */
    private fun visibleNames(): Set<String> {
        val names = mutableSetOf<String>()
        var env: Environment? = this
        while (env != null) {
            names.addAll(env.values.keys)
            env = env.enclosing
        }
        return names
    }

    fun getRaw(name: String): Any? {
        var env: Environment? = this
        while (env != null) {
            val values = env.values
            if (values.containsKey(name)) return values[name]
            env = env.enclosing
        }
        return null
    }

    fun assign(name: Token, value: Any?) {
        var env: Environment? = this
        while (env != null) {
            if (env.constants.contains(name.lexeme)) {
                throw SakhrError.RuntimeError(
                    "لا يمكن تغيير قيمة '${name.lexeme}' لأنه معرّف بـ 'ألزم' (ثابت).",
                    name.location,
                    "عرّفه بـ 'ليكن' بدلاً من 'ألزم' إذا احتجت إلى تغيير قيمته لاحقاً.",
                    length = name.lexeme.length
                )
            }
            if (env.values.containsKey(name.lexeme)) {
                env.values[name.lexeme] = value
                return
            }
            env = env.enclosing
        }
        throw SakhrError.RuntimeError(
            "المتغير '${name.lexeme}' غير معرّف، فلا يمكن إسناد قيمة إليه.",
            name.location,
            DiagnosticEngine.findClosest(name.lexeme, visibleNames())?.let { "هل قصدت '$it'؟" }
                ?: "عرّفه أولاً باستخدام 'ليكن ${name.lexeme} = ...'.",
            length = name.lexeme.length
        )
    }
}
