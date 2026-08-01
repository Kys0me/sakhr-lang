package me.kys.sakhr.lang

data class ProjectConfig(
    val name: String,
    val version: String,
    val rootDir: String
)

data class Module(
    val name: String,
    val path: String,
    val statements: List<Stmt>,
    val isStdLib: Boolean = false
)

data class ImportInfo(
    val path: List<String>,
    val isStdLib: Boolean,
    val location: Location
)
