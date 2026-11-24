package com.wangzan101.mika.cap.web.cdp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** One brand and version as reported by the engine (one version each taken from UA-CH's brands / fullVersionList). */
data class Brand(val brand: String, val major: String, val full: String)

/**
 * Identity of a desktop web page: the Linux desktop UA and UA-CH, reusing this device's WebView engine version as-is and the engine's own brands (minus Android WebView).
 * Everything the page can read must agree with this identity: UA / UA-CH / navigator.platform plus viewport, screen, and pixel ratio are set by emulation ([userAgentOverride],
 * [metricsOverride], [emulationCalls]); what emulation cannot set (touch capability, pointer and hover media queries, window frame, WebView-specific traces)
 * is filled in by [identityScript], injected at document start. The time zone is left alone and follows the device; the language defaults to the device too, and when the user has picked a language in Claire
 * the request headers and navigator.language(s) are switched to that one (see [PageLanguage]).
 * Mobile sites are never changed and keep the device's real mobile identity.
 */
object DesktopProfile {
    /** Desktop viewport width (CSS pixels). */
    const val WIDTH = 1280
    const val FALLBACK_HEIGHT = 800
    /** Host display of the desktop tab: draws the desktop viewport at 1 image pixel per CSS pixel. */
    const val HOST_WIDTH = WIDTH
    const val HOST_HEIGHT = 800
    const val HOST_DPI = 160
    const val NAVIGATOR_PLATFORM = "Linux x86_64"
    const val PLATFORM = "Linux"
    /** Height of the parts outside the page in a desktop browser window (tab strip, address bar): the window frame is this much taller than the page viewport. */
    const val WINDOW_CHROME_PX = 85
    private const val WEBVIEW_BRAND = "Android WebView"
    private val CHROME = Regex("""Chrome/(\d+)((?:\.\d+)*)""")

    /** Extract the version from the engine's default UA: (major, full). */
    fun version(defaultUa: String): Pair<String, String>? =
        CHROME.find(defaultUa)?.let { m -> m.groupValues[1] to (m.groupValues[1] + m.groupValues[2]) }

    /** By convention the UA only writes the major version (the rest are zeros). */
    fun userAgent(major: String): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"

    /** Drop Android WebView from the brands the engine gives; when unavailable, use Chromium plus one placeholder brand. */
    fun brands(engine: List<Brand>?, major: String, full: String): List<Brand> =
        engine?.filter { it.brand != WEBVIEW_BRAND }?.takeIf { l -> l.any { it.brand == "Chromium" } }
            ?: listOf(Brand("Chromium", major, full), Brand("Not_A Brand", "24", "24.0.0.0"))

    /**
     * Parameters for `Emulation.setUserAgentOverride`. It carries no acceptLanguage here: when following the system, the language stays the user's;
     * when the user has picked a language in Claire it is added by [PageLanguage.withLanguage].
     */
    fun userAgentOverride(major: String, full: String, brands: List<Brand>): JsonObject = buildJsonObject {
        put("userAgent", userAgent(major))
        put("platform", NAVIGATOR_PLATFORM)
        put("userAgentMetadata", metadata(brands, full, PLATFORM, "", "x86", "", mobile = false, bitness = "64", wow64 = false))
    }

    /** CDP's `Emulation.UserAgentMetadata` (UA-CH); shared by the desktop identity and the mobile default identity ([PageLanguage.mobileOverride]). */
    fun metadata(
        brands: List<Brand>, fullVersion: String, platform: String, platformVersion: String,
        architecture: String, model: String, mobile: Boolean, bitness: String, wow64: Boolean,
    ): JsonObject = buildJsonObject {
        put("brands", JsonArray(brands.map { b -> buildJsonObject { put("brand", b.brand); put("version", b.major) } }))
        put("fullVersionList", JsonArray(brands.map { b -> buildJsonObject { put("brand", b.brand); put("version", b.full) } }))
        put("fullVersion", fullVersion)
        put("platform", platform)
        put("platformVersion", platformVersion)
        put("architecture", architecture)
        put("model", model)
        put("mobile", mobile)
        put("bitness", bitness)
        put("wow64", wow64)
    }

    /** The viewport height scales the host's aspect ratio up to the desktop width so the whole page exactly fills the host; 800 when the host size is unknown. */
    fun height(viewW: Int, viewH: Int): Int =
        if (viewW > 0 && viewH > 0) (WIDTH.toDouble() * viewH / viewW).roundToInt() else FALLBACK_HEIGHT

    /**
     * Scale of the emulated image: without scaling, 1 CSS pixel takes [density] view pixels (the page's devicePixelRatio without emulation),
     * and a desktop-width page may only show its left part; scaling so the whole [WIDTH] page exactly fills the view width makes the view-pixel-to-CSS-pixel ratio viewW / WIDTH
     * (screen-reader conversion uses it). On a desktop host the view width is [WIDTH], so it is one-to-one.
     */
    fun fitScale(viewW: Int, density: Float): Double? =
        if (viewW > 0 && density > 0f) viewW / (WIDTH * density.toDouble()) else null

    /**
     * Parameters for `Emulation.setDeviceMetricsOverride`: desktop width, pixel ratio 1, not mobile; [scale] shrinks the whole page into the host width.
     * The screen is computed as 'window filling the whole screen': the width equals the viewport, the height is the viewport plus the browser window frame ([WINDOW_CHROME_PX]), matching the outerHeight the script reports.
     */
    fun metricsOverride(viewW: Int, viewH: Int, scale: Double?): JsonObject = buildJsonObject {
        val h = height(viewW, viewH)
        put("width", WIDTH)
        put("height", h)
        put("deviceScaleFactor", 1)
        put("mobile", false)
        put("screenWidth", WIDTH)
        put("screenHeight", h + WINDOW_CHROME_PX)
        put("screenOrientation", buildJsonObject {
            val landscape = WIDTH >= h + WINDOW_CHROME_PX
            put("type", if (landscape) "landscapePrimary" else "portraitPrimary")
            put("angle", 0)
        })
        scale?.let { put("scale", it) }
    }

    /**
     * Extra emulation commands a desktop page must send beyond UA and viewport (method, params): no touch emulation, mouse does not derive touch events,
     * pointer and hover are reported as a mouse (when the engine does not honor these media features this entry has no effect and [identityScript] takes over).
     * Each is sent separately, so one being rejected does not affect the others.
     */
    fun emulationCalls(): List<Pair<String, JsonObject>> = listOf(
        "Emulation.setTouchEmulationEnabled" to buildJsonObject { put("enabled", false) },
        "Emulation.setEmitTouchEventsForMouse" to buildJsonObject { put("enabled", false) },
        "Emulation.setEmulatedMedia" to buildJsonObject {
            put("features", JsonArray(DESKTOP_MEDIA.map { (n, v) -> buildJsonObject { put("name", n); put("value", v) } }))
        },
    )

    /**
     * Which call the desktop viewport uses: the agent's tab uses emulation to shrink the whole [WIDTH] page into the view ([metricsOverride]; screen-reader and input coordinates convert by [scale]);
     * a tab the user personally looks at and taps ([handOperated]) gets no viewport emulation (that one is reverted), leaving it to WebView's own wide viewport and overview mode —
     * the whole page is shrunk onto the screen on open and pinch-zoom works; emulation pins the zoom, so it cannot be zoomed. UA, UA-CH, and the identity patch are the same for both kinds of tab.
     */
    fun viewportCall(handOperated: Boolean, viewW: Int, viewH: Int, scale: Double?): Pair<String, JsonObject> =
        if (handOperated) "Emulation.clearDeviceMetricsOverride" to JsonObject(emptyMap())
        else "Emulation.setDeviceMetricsOverride" to metricsOverride(viewW, viewH, scale)

    /** Revert the media features changed by [emulationCalls] when switching back to mobile (an empty list reverts them). */
    fun clearMediaCall(): Pair<String, JsonObject> =
        "Emulation.setEmulatedMedia" to buildJsonObject { put("features", JsonArray(emptyList())) }

    private val DESKTOP_MEDIA = listOf("pointer" to "fine", "any-pointer" to "fine", "hover" to "hover", "any-hover" to "hover")

    /**
     * The patch injected at the start of every document (including frames) for the desktop version, covering only what emulation cannot set:
     * - touch: navigator.maxTouchPoints is 0, the ontouch* handler properties are removed, and `document.createEvent('TouchEvent')` reports unsupported like desktop;
     * - pointer and hover: pointer / any-pointer / hover / any-hover in matchMedia answer as a mouse (everything else is passed to the engine unchanged),
     *   and the returned object's media is still the page-provided text (changed on the prototype getter so no own properties appear on the object);
     * - window frame: outerWidth = innerWidth, outerHeight = innerHeight + [WINDOW_CHROME_PX];
     * - navigator.platform is [NAVIGATOR_PLATFORM] (consistent with the UA even without DevTools); navigator.webdriver is false;
     *   navigator.connection drops the phone-only type; when window.chrome is absent an empty object is added (it is always there on desktop browsers).
     * Replaced getters call the original first (still throwing Illegal invocation for a wrong receiver) and then give the new value; replaced methods keep the original length;
     * their source is shown as a built-in function, and toString from other windows looks the same (the patch source is identical in every window, so it is recognized by source).
     * Same-origin frames created by page scripts (about:blank, srcdoc) do not necessarily go through document-start injection: the first time they are reached through contentWindow / contentDocument
     * the same patch is added in the frame's own environment (falling back to patching this environment when the frame forbids eval). Windows that already report 0 touch points are not patched again.
     * It does not touch UA, language, time zone, or hardware parameters.
     */
    fun identityScript(): String = IDENTITY_SCRIPT
        .replace("__PLATFORM__", NAVIGATOR_PLATFORM)
        .replace("__CHROME_PX__", WINDOW_CHROME_PX.toString())

    private val IDENTITY_SCRIPT = """
(function desktopIdentity(W) {
  'use strict';
  try { if (!W.navigator || W.navigator.maxTouchPoints === 0) return; } catch (e) { return; }
  var FP = W.Function.prototype, readSource = FP.toString, ours = new Set(), lists = new WeakMap();
  function ownFn(fn) { ours.add(readSource.call(fn)); return fn; }
  function accessor(target, prop, answer) {
    var old = target && Object.getOwnPropertyDescriptor(target, prop);
    if (!old || typeof old.get !== 'function' || !old.configurable) return;
    var realGet = old.get;
    var get = ownFn(Object.getOwnPropertyDescriptor({ get [prop]() { return answer(realGet.call(this), this); } }, prop).get);
    Object.defineProperty(target, prop, { get: get, set: old.set, enumerable: old.enumerable, configurable: true });
  }
  function method(target, prop, body) {
    var old = target && Object.getOwnPropertyDescriptor(target, prop);
    if (!old || typeof old.value !== 'function' || !old.configurable) return;
    var real = old.value;
    var fn = ownFn({ [prop]() { return body.call(this, real, arguments); } }[prop]);
    Object.defineProperty(fn, 'length', { value: real.length, configurable: true });
    Object.defineProperty(target, prop, { value: fn, writable: old.writable, enumerable: old.enumerable, configurable: true });
  }
  function constant(v) { return function () { return v; }; }

  method(FP, 'toString', function (real, args) {
    var text = real.apply(this, args);
    return ours.has(text) ? 'function ' + this.name + '() { [native code] }' : text;
  });

  var nav = W.Navigator && W.Navigator.prototype;
  accessor(nav, 'maxTouchPoints', constant(0));
  accessor(nav, 'platform', constant('__PLATFORM__'));
  accessor(nav, 'webdriver', constant(false));

  [W, W.Document && W.Document.prototype, W.Element && W.Element.prototype,
   W.HTMLElement && W.HTMLElement.prototype, W.SVGElement && W.SVGElement.prototype].forEach(function (o) {
    if (!o) return;
    ['ontouchstart', 'ontouchend', 'ontouchmove', 'ontouchcancel'].forEach(function (p) {
      var d = Object.getOwnPropertyDescriptor(o, p);
      if (d && d.configurable) delete o[p];
    });
  });
  method(W.Document && W.Document.prototype, 'createEvent', function (real, args) {
    if (args.length && /^\s*touchevents?\s*${'$'}/i.test(String(args[0]))) {
      throw new W.DOMException("Failed to execute 'createEvent' on 'Document': The provided event type ('" + args[0] + "') is invalid.", 'NotSupportedError');
    }
    return real.apply(this, args);
  });

  var FEATURE = /\(\s*(any-)?(pointer|hover)\s*(?::\s*([a-z-]+)\s*)?\)/gi;
  var TRUE_Q = '(min-width: 0px)', FALSE_Q = '(max-width: 0px) and (min-width: 1px)';
  function asMouse(all, any, feature, value) {
    if (!value) return TRUE_Q;
    value = value.toLowerCase();
    return (feature.toLowerCase() === 'pointer' ? value === 'fine' : value === 'hover') ? TRUE_Q : FALSE_Q;
  }
  method(W, 'matchMedia', function (real, args) {
    if (!args.length) return real.apply(this, args);
    var query = String(args[0]);
    FEATURE.lastIndex = 0;
    if (!FEATURE.test(query)) return real.apply(this, args);
    var list = real.call(this, query.replace(FEATURE, asMouse));
    lists.set(list, real.call(this, query).media);
    return list;
  });
  accessor(W.MediaQueryList && W.MediaQueryList.prototype, 'media', function (v, self) { return lists.has(self) ? lists.get(self) : v; });

  accessor(W, 'outerWidth', function () { return W.innerWidth; });
  accessor(W, 'outerHeight', function () { return W.innerHeight + __CHROME_PX__; });

  var conn = W.NetworkInformation && W.NetworkInformation.prototype;
  ['type', 'ontypechange'].forEach(function (p) {
    var d = conn && Object.getOwnPropertyDescriptor(conn, p);
    if (d && d.configurable) delete conn[p];
  });

  if (!('chrome' in W)) Object.defineProperty(W, 'chrome', { value: {}, writable: true, enumerable: true, configurable: true });

  // Frames created by script (about:blank / srcdoc) do not necessarily go through document-start injection: backfill the same payload when one is first retrieved
  var self = desktopIdentity, source = readSource.call(self);
  function adopt(child) {
    try { if (!child || child === W || child.navigator.maxTouchPoints === 0) return; } catch (e) { return; }
    try { child.Function('W', 'return (' + source + ')(W);')(child); } catch (e) { try { self(child); } catch (e2) {} }
  }
  var frame = W.HTMLIFrameElement && W.HTMLIFrameElement.prototype;
  accessor(frame, 'contentWindow', function (v) { adopt(v); return v; });
  accessor(frame, 'contentDocument', function (v) { if (v) adopt(v.defaultView); return v; });
})(window);
"""
}
