package com.wangzan101.mika.proactive

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/** How the notification listener is declared in the manifest: disabled by default (with the feature off, Claire is absent from the system's "notification access" settings), and it only filters ongoing notifications. */
class ProactiveManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    @Test fun listenerIsDisabledByDefaultAndOnlyFiltersOngoing() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        val s = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == ".proactive.NoticeListenerService" }
        assertEquals("false", s.getAttributeNS(ns, "enabled"))
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", s.getAttributeNS(ns, "permission"))
        val metas = s.getElementsByTagName("meta-data")
        val filters = (0 until metas.length).map { metas.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == "android.service.notification.disabled_filter_types" }
        assertEquals("ongoing", filters.getAttributeNS(ns, "value"))
    }
}
