package com.wangzan101.mika.cap.phone.shell

/**
 * When the system APIs cannot tell which activities can receive an Intent (on some systems the app-side query
 * reads additional settings and throws if the shell identity cannot read them), ask
 * `cmd package query-activities` instead: the query runs in the system service and only honors the shell
 * identity. Does not depend on Android classes, so unit tests can call it directly.
 */
internal object ActivityQuery {
    /** Command arguments: [action], optional [type], [uri], [category]; with [packageName] only that package is queried; [user] selects which user profile (current user by default). */
    fun args(action: String, type: String? = null, uri: String? = null, category: String? = null, packageName: String? = null, user: String = "current"): List<String> =
        listOf("cmd", "package", "query-activities", "--brief", "--user", user, "-a", action) +
            listOfNotNull(type?.let { "-t" }, type) + listOfNotNull(uri?.let { "-d" }, uri) +
            listOfNotNull(category?.let { "-c" }, category) + listOfNotNull(packageName?.let { "-p" }, packageName)

    /** Extracts each activity's (package name, fully qualified class name) from the output; short class names starting with a dot get the package name prepended. */
    fun parse(output: String): List<Pair<String, String>> = output.lineSequence().mapNotNull { line ->
        val m = COMPONENT.matchEntire(line.trim()) ?: return@mapNotNull null
        val (pkg, cls) = m.destructured
        pkg to if (cls.startsWith(".")) pkg + cls else cls
    }.distinct().toList()

    private val COMPONENT = Regex("""([A-Za-z][\w.]*)/([\w.$]+)""")
}
