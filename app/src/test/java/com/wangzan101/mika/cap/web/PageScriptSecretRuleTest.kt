package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.PageScriptProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** web_script's "inputs whose values are not read" and the screen-reading script page.js follow the same rule: the autocomplete lists on both sides must match. */
class PageScriptSecretRuleTest {
    private val pageJs = File("src/main/assets/web/page.js").readText()

    private fun tokens(name: String): Set<String> {
        val body = Regex("var $name = \\{([^}]*)\\}").find(pageJs)!!.groupValues[1]
        return Regex("'([^']+)'").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test fun autocompleteListMatchesPageScript() {
        assertEquals(tokens("PASSWORD_AUTOCOMPLETE") + tokens("SECRET_AUTOCOMPLETE"), PageScriptProtocol.SECRET_AUTOCOMPLETE.toSet())
    }
}
