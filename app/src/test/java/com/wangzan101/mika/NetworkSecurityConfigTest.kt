package com.wangzan101.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guard for the network security config: self-hosted services on a company intranet rely on these two declarations to connect directly (plain http + user-installed CAs),
 * and removing either one degrades this to "encrypted endpoints that trust only system certificates", so the scenario in #361 cannot connect again.
 */
class NetworkSecurityConfigTest {

    private fun baseConfig(): Element {
        val file = File("src/main/res/xml/network_security_config.xml")
        assertTrue("network security config not found from ${File(".").absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val configs = doc.getElementsByTagName("base-config")
        assertEquals("expected exactly one base-config", 1, configs.length)
        return configs.item(0) as Element
    }

    /** Model services users run on their own machine or LAN usually speak only http; remove this and none of them can connect. */
    @Test
    fun cleartextStaysPermitted() {
        assertEquals("true", baseConfig().getAttribute("cleartextTrafficPermitted"))
    }

    /**
     * Self-hosted endpoints signed by an internal company CA (GPUStack/vLLM and the like) depend entirely on the user CA entry: once trust anchors are declared explicitly, the system no longer
     * accepts user certificates by default, and a regular user without root cannot install into the system trust store. Installing a CA requires the user to act in system settings.
     */
    @Test
    fun trustAnchorsIncludeUserInstalledCertificates() {
        val anchors = baseConfig().getElementsByTagName("certificates")
        val sources = (0 until anchors.length).map { (anchors.item(it) as Element).getAttribute("src") }
        assertEquals(setOf("system", "user"), sources.toSet())
    }
}
