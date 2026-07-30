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
        throw SakhrError.RuntimeError("المتغير '${name.lexeme}' غير معرف.", name.location)
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
                throw SakhrError.RuntimeError("لا يمكن إعادة تعيين قيمة للمتغير '${name.lexeme}' لأنه معرف كـ 'ألزم'.", name.location)
            }
            if (env.values.containsKey(name.lexeme)) {
                env.values[name.lexeme] = value
                return
            }
            env = env.enclosing
        }
        throw SakhrError.RuntimeError("المتغير '${name.lexeme}' غير معرف.", name.location)
    }
}
