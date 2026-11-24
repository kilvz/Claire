package com.wangzan101.mika.core.cap.web

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Short-form / integer / octal IPv4 notations are localhost or intranet addresses too, so they must not bypass the literal-IP check made before a download. */
class DownloadShortIpv4Test {
    @Test fun shortIntegerAndOctalIpv4LiteralsAreRefused() {
        for (u in listOf(
            "http://127.1/", "http://2130706433/", "http://0300.0250.0.1/",
            "http://192.168.1/", "http://10.1/", "http://169.254.169.254/",
        )) assertNotNull(u, DownloadAddressPolicy.refusal(u))
    }

    @Test fun ordinaryDomainsAndPublicLiteralsStillPass() {
        for (u in listOf("https://example.com/a.pdf", "http://8.8.8.8/x", "http://172.32.0.1/")) {
            assertNull(u, DownloadAddressPolicy.refusal(u))
        }
    }
}
