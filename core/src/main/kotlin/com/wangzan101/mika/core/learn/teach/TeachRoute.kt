package com.wangzan101.mika.core.learn.teach

/** One route step after folding. [kind] being null means it is a synthesized launch/switch step, not an action from the demonstration. */
data class RouteStep(val app: String?, val text: String, val kind: TeachKind?)

/** The apps actually used in this demonstration. [browser] is decided by the caller from the system default browser and is only a hint. */
data class RouteApp(val packageName: String, val label: String, val browser: Boolean)

data class TeachRoute(
    val steps: List<RouteStep>,
    val apps: List<RouteApp>,
    val folded: List<String>,
) {
    /**
     * The deterministic first step that turns "what the person did" into "how Claire does it":
     * going back to the home screen, finding an icon and tapping the app only serve to get a person into the app, so the skill writes them as "launch/switch to/back to <App>".
     *
     * When the launcher cannot be identified ([launcherPackages] is empty), everything is kept as-is so no step is deleted by mistake.
     * When the browser cannot be identified, only the role hint is skipped; folding is unaffected.
     */
    companion object {
        fun fold(
            steps: List<TeachStep>,
            launcherPackages: Set<String>,
            browserPackages: Set<String>,
            label: (String) -> String,
        ): TeachRoute {
            if (launcherPackages.isEmpty()) return failOpen(steps, browserPackages, label)

            val apps = LinkedHashMap<String, RouteApp>()
            val out = ArrayList<RouteStep>(steps.size)
            val folded = ArrayList<String>()
            var lastApp: String? = null
            var viaLauncher = false

            steps.forEach { step ->
                val pkg = step.packageName
                if (pkg != null && pkg in launcherPackages) {
                    folded += foldLine(step)
                    viaLauncher = true
                    return@forEach
                }
                // A window change is not an action: it may be the minus-one screen, the input method or a system overlay, so the package must not be taken as an app used by this demonstration because of it.
                if (step.kind == TeachKind.Window) return@forEach
                if (pkg == null) {
                    out += RouteStep(null, teachLine(step), step.kind)
                    return@forEach
                }
                val name = label(pkg).trim().ifEmpty { pkg }
                val previous = lastApp
                when {
                    previous == null && viaLauncher ->
                        out += RouteStep(pkg, "Launch $name", null)
                    viaLauncher && pkg == previous ->
                        out += RouteStep(pkg, "Back to $name", null)
                    previous != null && pkg != previous ->
                        out += RouteStep(pkg, (if (pkg in apps) "Back to " else "Switch to ") + name, null)
                }
                lastApp = pkg
                viaLauncher = false
                apps.getOrPut(pkg) { RouteApp(pkg, name, pkg in browserPackages) }
                // A window switch is not an action: it is the result of the previous step, or is already expressed by a synthesized step.
                if (step.kind != TeachKind.Window) out += RouteStep(pkg, teachLine(step), step.kind)
            }
            return TeachRoute(out, apps.values.toList(), folded)
        }

        private fun failOpen(
            steps: List<TeachStep>,
            browserPackages: Set<String>,
            label: (String) -> String,
        ): TeachRoute {
            val apps = LinkedHashMap<String, RouteApp>()
            steps.forEach { step ->
                val pkg = step.packageName ?: return@forEach
                apps.getOrPut(pkg) { RouteApp(pkg, label(pkg).trim().ifEmpty { pkg }, pkg in browserPackages) }
            }
            return TeachRoute(
                steps = steps.map { RouteStep(it.packageName, teachLine(it), it.kind) },
                apps = apps.values.toList(),
                folded = emptyList(),
            )
        }

        /**
         * Only used to show a person "what was left out" and to give the model a comparison. It carries no package names or window details.
         */
        private fun foldLine(step: TeachStep): String {
            val target = step.nodeName ?: step.viewId
            return when (step.kind) {
                TeachKind.Window -> step.window?.let { "switched to \"$it\"" } ?: "switched screens"
                TeachKind.Click -> target?.let { "tapped \"$it\"" } ?: "tapped"
                TeachKind.LongClick -> target?.let { "long-pressed \"$it\"" } ?: "long-pressed"
                TeachKind.Type -> target?.let { "typed into \"$it\"" } ?: "typed"
                TeachKind.Scroll -> "scrolled"
            }
        }
    }
}
