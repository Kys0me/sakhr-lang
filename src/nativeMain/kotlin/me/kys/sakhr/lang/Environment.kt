package me.kys.sakhr.lang

class Environment(private val enclosing: Environment? = null) {
    private val values = mutableMapOf<String, Any?>()
    private val constants = mutableSetOf<String>()

    fun define(name: String, value: Any?, isConstant: Boolean) {
        values[name] = value
        if (isConstant) constants.add(name)
    }

    fun get(name: Token): Any? {
        if (values.containsKey(name.lexeme)) return values[name.lexeme]
        if (enclosing != null) return enclosing.get(name)
        throw SakhrError.RuntimeError("المتغير '${name.lexeme}' غير معرف.", name.location)
    }

    fun getRaw(name: String): Any? {
        if (values.containsKey(name)) return values[name]
        if (enclosing != null) return enclosing.getRaw(name)
        return null
    }

    fun assign(name: Token, value: Any?) {
        if (constants.contains(name.lexeme)) {
            throw SakhrError.RuntimeError("لا يمكن إعادة تعيين قيمة للمتغير '${name.lexeme}' لأنه معرف كـ 'ألزم'.", name.location)
        }
        
        if (values.containsKey(name.lexeme)) {
            values[name.lexeme] = value
            return
        }

        if (enclosing != null) {
            enclosing.assign(name, value)
            return
        }

        throw SakhrError.RuntimeError("المتغير '${name.lexeme}' غير معرف.", name.location)
    }
}
