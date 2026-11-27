package com.wangzan101.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Manifest settings that crash the process on startup, caught here on the desktop first (the kind only a real device would expose). */
class ManifestProvidersTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun fileProvidersGrantUriPermissionsAndStayPrivate() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found from ${File(".").absolutePath}", manifest.exists())
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val providers = doc.getElementsByTagName("provider")
        var fileProviders = 0
        for (i in 0 until providers.length) {
            val p = providers.item(i) as Element
            val metas = p.getElementsByTagName("meta-data")
            val isFileProvider = (0 until metas.length).any {
                (metas.item(it) as Element).getAttributeNS(androidNs, "name") == "android.support.FILE_PROVIDER_PATHS"
            }
            if (!isFileProvider) continue
            fileProviders++
            val name = p.getAttributeNS(androidNs, "name")
            // FileProvider.attachInfo throws SecurityException when grantUriPermissions is not true, and the process will not start
            assertEquals("$name grantUriPermissions", "true", p.getAttributeNS(androidNs, "grantUriPermissions"))
            assertEquals("$name exported", "false", p.getAttributeNS(androidNs, "exported"))
        }
        assertTrue("expected at least one FileProvider", fileProviders > 0)
    }

    @Test
    fun helperHandoffProviderIsOnlyReachableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val providers = doc.getElementsByTagName("provider")
        val p = (0 until providers.length).map { providers.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".shell.HelperHandoffProvider" }
        // shell must be able to reach it; a regular app lacks this permission and cannot even obtain the provider
        assertEquals("true", p.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", p.getAttributeNS(androidNs, "permission"))
        assertEquals("\${applicationId}.helper", p.getAttributeNS(androidNs, "authorities"))
    }

    /** The computer-connection service is exported only so adb shell can start it: a regular app lacks this permission, so it can neither start it nor toggle the USB mode. */
    @Test
    fun computerAccessServiceIsOnlyStartableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        val s = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".mcp.McpService" }
        assertEquals("true", s.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", s.getAttributeNS(androidNs, "permission"))
        assertEquals("specialUse", s.getAttributeNS(androidNs, "foregroundServiceType"))
        // No intent-filter: it can only be started explicitly by component name
        assertEquals(0, s.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun standbyRecoveryServiceOnlyAcceptsSystemJobBinding() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        val service = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".calls.CallStandbyRecoveryJob" }
        assertEquals("false", service.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.BIND_JOB_SERVICE", service.getAttributeNS(androidNs, "permission"))
    }

    /** 清单里登记的组件都按全名找得到类(搬了代码的入口留着同名的薄壳)。 */
    @Test
    fun everyComponentClassExists() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val names = listOf("application", "activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        assertTrue(names.size > 5)
        for (n in names) {
            val full = if (n.startsWith(".")) "com.wangzan101.mika$n" else n
            assertTrue("$full is not a class", runCatching { Class.forName(full, false, javaClass.classLoader) }.isSuccess)
        }
    }

    /**
     * Screens and services in the test APK manifest run in the test package's own process, which has no Kotlin standard library (dependencies duplicated with the app are not packed into the test APK):
     * each must have a Java source file with the same name. If the class name does not match, or it is written in Kotlin, it crashes on a real device the moment it opens, and a screen reader finds only a blank screen.
     */
    @Test
    fun testApkComponentsAreJavaClassesWithMatchingNames() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/androidTest/AndroidManifest.xml"))
        val names = listOf("activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        assertTrue(names.isNotEmpty())
        for (n in names) {
            val path = "src/androidTest/java/${n.replace('.', '/')}"
            assertTrue("$n has no Java source at $path.java", File("$path.java").isFile)
        }
    }

    /**
     * Photo attachments go through the system camera: declare that the camera is used, but not as required (devices without a camera still install as usual). This declaration must not be removed:
     * some systems classify apps by APK characteristics, and without it Claire may be treated as a game and get its refresh rate capped.
     */
    @Test
    fun cameraIsDeclaredAsOptionalFeature() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val features = doc.getElementsByTagName("uses-feature")
        val camera = (0 until features.length).map { features.item(it) as Element }
            .singleOrNull { it.getAttributeNS(androidNs, "name") == "android.hardware.camera" }
        assertTrue("uses-feature android.hardware.camera is missing", camera != null)
        assertEquals("false", camera!!.getAttributeNS(androidNs, "required"))
    }

    /**
     * Location permissions imply "location hardware is required"; web-based location is not an essential feature, so declare it non-required and devices without GPS can still install.
     */
    @Test
    fun locationIsDeclaredAsAnOptionalFeature() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = doc.getElementsByTagName("uses-feature")
        val features = (0 until nodes.length).map { nodes.item(it) as Element }
            .associateBy { it.getAttributeNS(androidNs, "name") }
        for (name in listOf("android.hardware.location", "android.hardware.location.gps")) {
            val feature = features[name]
            assertTrue("uses-feature $name is missing", feature != null)
            assertEquals(name, "false", feature!!.getAttributeNS(androidNs, "required"))
        }
    }

    /**
     * The compatibility self-check test page is exported only so the built-in assistant (shell) can open it: the caller must hold INTERACT_ACROSS_USERS_FULL, so a regular app cannot open it;
     * there is no intent-filter, so it can only be opened by component name; it runs in its own task and stays out of recents. The class name the assistant uses matches the manifest.
     */
    @Test
    fun selfCheckTargetIsOnlyStartableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val a = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".selfcheck.SelfCheckTargetActivity" }
        assertEquals("true", a.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", a.getAttributeNS(androidNs, "permission"))
        assertEquals(0, a.getElementsByTagName("intent-filter").length)
        assertEquals(com.wangzan101.mika.cap.phone.shell.SystemIntents.OWN_PAGE_CLASS, "com.wangzan101.mika" + a.getAttributeNS(androidNs, "name"))
        assertEquals("true", a.getAttributeNS(androidNs, "excludeFromRecents"))
        assertTrue(a.hasAttributeNS(androidNs, "taskAffinity"))
        assertEquals("", a.getAttributeNS(androidNs, "taskAffinity"))
    }

    @Test fun capsuleTileIsBoundOnlyByAndroidQuickSettings() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        val tile = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".capsule.CapsuleTileService" }
        assertEquals("false", tile.getAttributeNS(androidNs, "enabled"))
        assertEquals("true", tile.getAttributeNS(androidNs, "exported"))
        assertEquals("@string/capsule_tile_label", tile.getAttributeNS(androidNs, "label"))
        assertEquals("@drawable/ic_notification", tile.getAttributeNS(androidNs, "icon"))
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.getAttributeNS(androidNs, "permission"))
        val actions = tile.getElementsByTagName("action")
        assertEquals(1, actions.length)
        assertEquals("android.service.quicksettings.action.QS_TILE",
            (actions.item(0) as Element).getAttributeNS(androidNs, "name"))
    }

    @Test fun capsuleTileRuntimeKeepsTheSameSystemSafeIcon() {
        val service = File("src/main/java/com/wangzan101/mika/capsule/CapsuleTileService.kt").readText()
        assertTrue(service.contains("Icon.createWithResource(this, R.drawable.ic_notification)"))
        assertTrue(!service.contains("Icon.createWithResource(this, R.mipmap.ic_launcher)"))
    }

    @Test fun capsuleTileLongPressOpensMikaInsteadOfAppDetails() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".ui.MainActivity" }
        val filters = main.getElementsByTagName("intent-filter")
        val preferences = (0 until filters.length).map { filters.item(it) as Element }.single { filter ->
            val actions = filter.getElementsByTagName("action")
            (0 until actions.length).any {
                (actions.item(it) as Element).getAttributeNS(androidNs, "name") ==
                    "android.service.quicksettings.action.QS_TILE_PREFERENCES"
            }
        }
        val categories = preferences.getElementsByTagName("category")
        assertTrue((0 until categories.length).any {
            (categories.item(it) as Element).getAttributeNS(androidNs, "name") == "android.intent.category.DEFAULT"
        })
    }

    @Test fun mainActivityAcceptsSingleAndMultipleSharedFiles() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".ui.MainActivity" }
        val filters = main.getElementsByTagName("intent-filter")
        for (actionName in listOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE")) {
            val matching = (0 until filters.length).map { filters.item(it) as Element }.filter { filter ->
                val actions = filter.getElementsByTagName("action")
                (0 until actions.length).any { (actions.item(it) as Element).getAttributeNS(androidNs, "name") == actionName }
            }
            assertEquals("Expected one intent-filter for $actionName", 1, matching.size)
            val filter = matching.single()
            val categories = filter.getElementsByTagName("category")
            assertTrue((0 until categories.length).any {
                (categories.item(it) as Element).getAttributeNS(androidNs, "name") == "android.intent.category.DEFAULT"
            })
            val data = filter.getElementsByTagName("data")
            assertTrue((0 until data.length).any {
                (data.item(it) as Element).getAttributeNS(androidNs, "mimeType") == "*/*"
            })
        }
    }

    /** The built-in assistant process is started by this full class name. */
    @Test
    fun helperEntryClassExists() {
        val c = Class.forName(com.wangzan101.mika.core.device.shell.HelperHandoff.HELPER_CLASS, false, javaClass.classLoader)
        assertTrue(c.methods.any { it.name == "main" && java.lang.reflect.Modifier.isStatic(it.modifiers) })
    }
}
