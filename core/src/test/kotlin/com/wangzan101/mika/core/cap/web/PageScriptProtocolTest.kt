package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageScriptProtocolTest {
    private fun details(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun parsesCalls() {
        val c = PageScriptProtocol.parseCall("""{"id":3,"op":"tap","args":{"ref":"@e2"}}""")!!
        assertEquals(3, c.id); assertEquals("tap", c.op); assertEquals("\"@e2\"", c.args["ref"].toString())
        // Missing args default to an empty object; log's id is 0
        assertEquals(JsonObject(emptyMap()), PageScriptProtocol.parseCall("""{"id":0,"op":"log"}""")!!.args)
    }

    @Test fun rejectsMalformedCalls() {
        for (bad in listOf("", "nope", "[]", """{"op":"tap"}""", """{"id":"1","op":"tap"}""", """{"id":-1,"op":"tap"}""", """{"id":1,"op":5}""")) {
            assertNull(bad, PageScriptProtocol.parseCall(bad))
        }
    }

    @Test fun settleEmbedsResultAsLiteral() {
        val r = buildJsonObject { put("ok", true); put("value", "a\"b</script>\n") }
        val e = PageScriptProtocol.settle(7, r)
        assertTrue(e.startsWith("globalThis.__mikaSettle && globalThis.__mikaSettle(7, {"))
        // JSON escaping guarantees the string cannot cut the expression short
        assertTrue("\\\"b" in e && "\\n" in e)
    }

    @Test fun runPassesCodeAsDataNotSource() {
        // The model's code is passed as a string to the pre-stored AsyncFunction for compilation, not spliced into the expression
        assertEquals("globalThis.__mikaRun(\"return '1\\\"'\")", PageScriptProtocol.run("return '1\"'"))
    }

    @Test fun errorLineFromStack() {
        // The compiled source has one line each for the function header, ") {", 'use strict'; and the inner arrow function, so the model's code starts at line 5
        val (msg, line) = PageScriptProtocol.error(details("""{"text":"Uncaught (in promise)","lineNumber":0,
            "exception":{"description":"TypeError: x\n    at eval (mika-script.js:7:9)\n    at mika-prelude.js:80:1"}}"""))
        assertEquals("TypeError: x", msg); assertEquals(3, line)
    }

    @Test fun errorWithoutScriptFrameHasNoLine() {
        val (msg, line) = PageScriptProtocol.error(details("""{"text":"Uncaught","lineNumber":2,
            "exception":{"description":"SyntaxError: Unexpected token '}'\n    at mika-prelude.js:80:16"}}"""))
        assertEquals("SyntaxError: Unexpected token '}'", msg); assertNull(line)
        val (m2, l2) = PageScriptProtocol.error(details("""{"text":"Uncaught 5","lineNumber":0}"""))
        assertEquals("Uncaught 5", m2); assertNull(l2)
    }

    @Test fun onlyPlainJavaScriptGlobalsAreAllowed() {
        // Only ECMAScript itself is allowed through; none of them is a browser global, and none can run a string as code
        val allowed = PageScriptProtocol.ALLOWED_GLOBALS
        for (g in listOf("document", "window", "self", "globalThis", "location", "fetch", "XMLHttpRequest", "setTimeout", "navigator", "history",
            "localStorage", "frames", "top", "parent", "eval", "Function", "console", "HTMLElement", "Element", "Node", "Event", "CSSStyleSheet",
            "Image", "Option", "WebSocket", "postMessage", "open", "structuredClone", "queueMicrotask", "WebAssembly")) {
            assertFalse(g, g in allowed)
        }
        for (g in listOf("JSON", "Math", "Array", "Object", "Promise", "Map", "Date", "RegExp", "Number", "String")) assertTrue(g, g in allowed)
    }

    @Test fun preludeShadowsEveryGlobalAndClosesStringEval() {
        val p = PageScriptProtocol.PRELUDE
        // The constructor is sealed only after AsyncFunction is captured; the shadowed names come from the global object and its entire prototype chain
        assertTrue(p.indexOf("const AsyncFunction =") < p.indexOf("'constructor', unavailable"))
        assertTrue("for (let o = G; o; o = O.getPrototypeOf(o)) for (const k of O.getOwnPropertyNames(o))" in p)
        for (k in listOf("fixed(G, 'eval', unavailable)", "fixed(G, 'Function', unavailable)", "fixed(G.Error, 'prepareStackTrace', undefined)")) assertTrue(k, k in p)
        // Strict mode, this as undefined, and every shadowed name are all passed undefined; only the last parameter is mika, and the return value is handed back solely as a JSON string
        assertTrue("new AsyncFunction(...shadow, 'mika', \"'use strict';\\nreturn (async () => {\\n\" + code" in p)
        assertTrue("apply(fn, undefined, [...blanks, mika])" in p)
        assertTrue("stringify(r)" in p)
        // The prelude never touches the DOM itself: no page object reaches the model's code through it
        for (dom in listOf("document.", "querySelector", "innerText", "HTMLElement")) assertFalse(dom, dom in p)
        assertTrue(p.trimEnd().endsWith("//# sourceURL=${PageScriptProtocol.PRELUDE_URL}"))
    }

    @Test fun preludeSendsCallsThroughTheOutbox() {
        val p = PageScriptProtocol.PRELUDE
        // It does not rely on DevTools bindings (that would require enabling Runtime): calls go into an outbox and are handed over by __mikaTake, whose name is shadowed in the model's code as well
        assertTrue("fixed(G, '__mikaTake'" in p)
        assertTrue(p.indexOf("fixed(G, '__mikaTake'") < p.indexOf("const shadow"))
        assertTrue(PageScriptProtocol.TAKE.startsWith("globalThis.__mikaTake("))
        for (op in listOf("snapshot", "read", "query", "queryAll", "text", "tap", "type", "scroll", "navigate", "waitText")) assertTrue(op, "call('$op'" in p)
    }

    @Test fun dynamicImportIsRejected() {
        assertNotNull(PageScriptProtocol.codeProblem("await import('data:text/javascript,1')"))
        assertNotNull(PageScriptProtocol.codeProblem("const m = import ( u )"))
        assertNotNull(PageScriptProtocol.codeProblem("import.meta"))
        assertNull(PageScriptProtocol.codeProblem("const important = 1; return important"))
    }

    @Test fun readerMasksSecretsWithThePageRule() {
        val r = PageScriptProtocol.READER
        // The same rule as page.js (for that list see PageScriptSecretRuleTest in app)
        assertEquals(listOf("password", "hidden"), PageScriptProtocol.SECRET_INPUT_TYPES)
        for (t in listOf("current-password", "new-password", "one-time-code", "cc-number", "cc-csc")) assertTrue(t, t in PageScriptProtocol.SECRET_AUTOCOMPLETE)
        assertTrue("k === 'value' && secret(el)" in r)
        assertTrue("secret(el) ? (el.value ? MASK : '')" in r)
        assertTrue("const textOf = (el) => (secret(el) ? ''" in r)
        assertTrue("k.startsWith('on')" in r)
        // The reader only hands back plain data
        assertTrue(PageScriptProtocol.read(buildJsonObject { put("mode", "count") }).startsWith("globalThis.__mikaRead({"))
    }

    @Test fun backstopSeesScriptInitiatedRequests() {
        fun req(stack: String) = details("""{"requestId":"1","request":{"url":"https://x/"},"initiator":{"type":"script","stack":$stack}}""")
        assertTrue(PageScriptProtocol.requestFromScript(req("""{"callFrames":[{"url":"mika-script.js","lineNumber":3}]}""")))
        // Counts inside an async parent chain too
        assertTrue(PageScriptProtocol.requestFromScript(req("""{"callFrames":[],"parent":{"callFrames":[{"url":"mika-prelude.js"}]}}""")))
        // Requests sent by the page's own scripts (including page handlers triggered by mika.tap) do not count
        assertFalse(PageScriptProtocol.requestFromScript(req("""{"callFrames":[{"url":"https://shop.example.com/app.js"}]}""")))
        assertFalse(PageScriptProtocol.requestFromScript(details("""{"requestId":"1","initiator":{"type":"other"}}""")))
    }

    @Test fun backstopStopsNavigationOutsideActions() {
        val nav = details("""{"frameId":"F1","reason":"scriptInitiated","url":"https://evil/"}""")
        assertTrue(PageScriptProtocol.navigationOutsideAction(nav, "F1", inAction = false))
        // While mika.tap / mika.navigate is running, a page navigation is the result of that action
        assertFalse(PageScriptProtocol.navigationOutsideAction(nav, "F1", inAction = true))
        // A subframe navigating on its own (ad carousel) is ignored
        assertFalse(PageScriptProtocol.navigationOutsideAction(nav, "F2", inAction = false))
    }
}
