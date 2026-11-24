package com.wangzan101.mika.cap.web.cdp

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLanguageTest {
    private val engine = listOf(Brand("Chromium", "152", "152.0.7977.64"), Brand("Android WebView", "152", "152.0.7977.64"))
    private val mobileUa = "Mozilla/5.0 (Linux; Android 14; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/152.0.7977.64 Mobile Safari/537.36"
    private val mobileMeta = DesktopProfile.metadata(engine, "152.0.7977.64", "Android", "14.0.0", "", "K", mobile = true, bitness = "", wow64 = false)

    @Test fun headerComesFromTheTag() {
        assertEquals("en-US,en", PageLanguage.acceptLanguage("en-Latn-US"))
        // The script subtag is not written
        assertEquals("sr-RS,sr", PageLanguage.acceptLanguage("sr-Latn-RS"))
        assertEquals("pt-BR,pt", PageLanguage.acceptLanguage("pt-BR"))
        assertEquals("id-ID,id", PageLanguage.acceptLanguage("in-ID"))
        // Without a region only the language is written
        assertEquals("fr", PageLanguage.acceptLanguage("fr"))
    }

    @Test fun desktopOverrideGetsTheLanguageOnlyWhenChosen() {
        val o = DesktopProfile.userAgentOverride("152", "152.0.7977.64", DesktopProfile.brands(engine, "152", "152.0.7977.64"))
        assertSame(o, PageLanguage.withLanguage(o, null))
        val chosen = PageLanguage.withLanguage(o, "en-US,en")
        assertEquals("en-US,en", chosen["acceptLanguage"]!!.jsonPrimitive.content)
        // The other identity entries stay unchanged
        assertEquals(o, chosen - "acceptLanguage")
    }

    @Test fun mobileFollowingTheSystemClearsTheOverride() {
        val o = PageLanguage.mobileOverride(mobileUa, mobileMeta, null)
        assertEquals("", o["userAgent"]!!.jsonPrimitive.content)
        assertEquals(setOf("userAgent"), o.keys)
        // When the default UA cannot be obtained, no substitute is fabricated either
        assertEquals("", PageLanguage.mobileOverride("", mobileMeta, "en-US,en")["userAgent"]!!.jsonPrimitive.content)
    }

    @Test fun mobileWithAChosenLanguageKeepsTheDefaultIdentity() {
        val o = PageLanguage.mobileOverride(mobileUa, mobileMeta, "en-US,en")
        assertEquals(mobileUa, o["userAgent"]!!.jsonPrimitive.content)
        assertEquals("en-US,en", o["acceptLanguage"]!!.jsonPrimitive.content)
        assertFalse("platform" in o)
        val meta = o["userAgentMetadata"]!!.jsonObject
        assertTrue(meta["mobile"]!!.jsonPrimitive.boolean)
        assertEquals("Android", meta["platform"]!!.jsonPrimitive.content)
        assertEquals("K", meta["model"]!!.jsonPrimitive.content)
        // The mobile brands are what the engine reports; Android WebView is kept
        assertEquals(listOf("Chromium", "Android WebView"), meta["brands"]!!.jsonArray.map { it.jsonObject["brand"]!!.jsonPrimitive.content })
        // When the default UA-CH cannot be obtained, only the UA and the language are included
        assertEquals(setOf("userAgent", "acceptLanguage"), PageLanguage.mobileOverride(mobileUa, null, "fr-FR,fr").keys)
    }
}
