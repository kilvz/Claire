package com.wangzan101.mika.core.tool

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The open_link documentation says file and script-like schemes are refused; browser-internal schemes should likewise never be handed to the system to open. */
class LinkPolicySchemeTest {
    @Test fun browserInternalSchemesAreRefused() {
        for (u in listOf("data:text/html,hello", "about:blank", "blob:https://example.com/abc")) {
            assertNotNull(u, LinkPolicy.check(null, u, null))
        }
    }

    @Test fun ordinaryAppAndWebLinksStillPass() {
        assertNull(LinkPolicy.check(null, "someapp://display/playlist", null))
        assertNull(LinkPolicy.check(null, "https://example.com/a", "com.example.browser"))
    }

    @Test fun previouslyBlockedSchemesStayBlocked() {
        for (u in listOf("intent://x#Intent;end", "file:///sdcard/a", "content://media/1", "javascript:alert(1)")) {
            assertNotNull(u, LinkPolicy.check(null, u, null))
        }
    }
}
