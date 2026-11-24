package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.script.JsSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The entry point through which `mika.*` calls in a page script (web_script) are handed to Claire. The port only relays:
 * each call goes to [call] as-is, and the return value (`{ok, value}` or `{ok:false, error, message}`) goes back to the script as-is.
 * Calls that read the page (query, queryAll, text) return `{ok:true, read:{…}}`: the arguments are already validated and counted, and the port reads by them in the read world.
 */
interface ScriptBridge {
    /** Throws [ScriptAbort] to end the whole script (limit exceeded, confirmation needed, stopped by a guard, page handed over to the user). */
    suspend fun call(op: String, args: JsonObject): JsonObject
    /** `mika.log`: awaits no reply. */
    fun log(text: String)
}

class ScriptAbort(val error: String, message: String) : Exception(message)

sealed interface ScriptRun {
    /** Finished; [valueJson] is the JSON of the return value (null when there is no return value). */
    data class Done(val valueJson: String?) : ScriptRun
    /** The whole page navigated away and the script stopped here. */
    data object Navigated : ScriptRun
    /** [error]: script_error | timeout | devtools_unavailable | no_tab_open, or the error code of a [ScriptAbort]. [line] is the line number in the script (1-based). */
    data class Failed(val error: String, val message: String, val line: Int? = null) : ScriptRun
}

/** One `mika.*` call within a page script. An [id] of 0 means no reply is awaited (log). */
data class ScriptCall(val id: Int, val op: String, val args: JsonObject)

/**
 * How page scripts are done: model code gets hold of no page object at all (by construction, not through a deny list).
 * - Script world: a fresh isolated world where the prelude does only two things - it compiles the model code with a previously saved AsyncFunction, shadowing every name on this world's global object
 *   (including the prototype chain) as a parameter passed undefined, allowing only [ALLOWED_GLOBALS] (pure ECMAScript built-ins that cannot reach the DOM);
 *   then it seals off the paths that run strings as code or reach the global object (the Function constructor family, eval, the call-stack APIs). All model code can use is `mika`,
 *   and every `mika` call is a JSON message sent to Claire, with nothing but JSON coming back.
 * - Read world: another isolated world holding only the fixed [READER] (written by Claire itself), which reads the DOM by validated arguments and returns pure data (values such as passwords are masked);
 *   model code lives in a different world and cannot reach any object it holds.
 */
object PageScriptProtocol {
    const val SOURCE_URL = "mika-script.js"
    const val PRELUDE_URL = "mika-prelude.js"
    /** Upper bound for a single mika.wait (milliseconds). */
    const val MAX_WAIT_MS = 10_000
    const val WORLD_PREFIX = "mika-script-"
    const val READER_WORLD_PREFIX = "mika-reader-"
    /** What a script gets when it reads the value of a password-like input (the same mask the run record uses). */
    const val SECRET_MASK = "***"

    /**
     * Inputs whose value is not read: the same rule as the page screen-read script (page.js) - password fields, hidden fields,
     * and those whose autocomplete marks them as a password, a bank card or a one-time code.
     */
    val SECRET_AUTOCOMPLETE = listOf("current-password", "new-password", "cc-number", "cc-csc", "cc-exp", "cc-exp-month", "cc-exp-year", "one-time-code")
    val SECRET_INPUT_TYPES = listOf("password", "hidden")

    /** Global names available in model code: only ECMAScript's own values and functions, none of which can reach the DOM or the global object. */
    val ALLOWED_GLOBALS = listOf(
        "undefined", "NaN", "Infinity", "Object", "Array", "Number", "Boolean", "String", "Symbol", "BigInt", "Date", "RegExp", "Promise",
        "Map", "Set", "WeakMap", "WeakSet", "WeakRef", "JSON", "Math", "Intl", "Reflect", "Proxy",
        "Error", "AggregateError", "EvalError", "RangeError", "ReferenceError", "SyntaxError", "TypeError", "URIError",
        "ArrayBuffer", "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array",
        "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array",
        "parseInt", "parseFloat", "isNaN", "isFinite", "encodeURI", "encodeURIComponent", "decodeURI", "decodeURIComponent", "Iterator",
    )

    /** Names that cannot be used as parameter names (strict-mode reserved words); eval is sealed off separately. */
    internal val RESERVED = listOf(
        "eval", "arguments", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum",
        "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null", "return", "super",
        "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield", "let", "static", "implements",
        "interface", "package", "private", "protected", "public", "await", "async", "mika",
    )

    /** How many lines of compiled source come before line 1 of model code (`(async function anonymous(…`, `) {`, `'use strict';`, `return (async () => {`). */
    private const val LINE_OFFSET = 4

    private val json = Json { ignoreUnknownKeys = true }

    private fun js(v: List<String>): String = v.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    /**
     * The prelude installed into the script world: it saves the originals it needs, seals off the paths that run strings as code, works out the names to shadow and defines `__mikaRun`.
     * Every mika call is numbered and put into the outbox, Kotlin keeps one [TAKE] pending (it hands back as soon as the next message arrives), and results are passed back with `__mikaSettle(id, r)`;
     * `__mikaEnd()` makes all later calls fail and makes the pending [TAKE] hand back null. DevTools bindings are not used: a binding requires Runtime to be enabled, and with it enabled the page can detect the debugger.
     */
    val PRELUDE: String = """
(() => {
  'use strict';
  const G = globalThis, O = Object;
  if (O.prototype.hasOwnProperty.call(G, '__mikaRun')) return;
  const AsyncFunction = O.getPrototypeOf(async function () {}).constructor;
  const stringify = JSON.stringify, apply = Reflect.apply, timer = G.setTimeout, freeze = O.freeze;
  const fixed = (o, k, v) => { try { O.defineProperty(o, k, { value: v, writable: false, enumerable: false, configurable: false }); } catch (e) {} };
  const unavailable = function () { throw new TypeError('not available in web_script: the script has no access to the page; use mika.*'); };
  // Running a string as code (it runs in the global scope and can reach the global object) and the call-stack APIs (they expose functions and this)
  for (const f of [function () {}, async function () {}, function* () {}, async function* () {}]) fixed(O.getPrototypeOf(f), 'constructor', unavailable);
  fixed(G, 'eval', unavailable);
  fixed(G, 'Function', unavailable);
  fixed(G.Error, 'prepareStackTrace', undefined);

  const pending = new Map();
  let seq = 0, ended = false;
  // Outbox: the __mikaTake pending from Kotlin waits in takers, and each arriving message is handed to one of them
  const outbox = [], takers = [];
  const post = (s) => { const t = takers.shift(); if (t) t(s); else outbox.push(s); };
  fixed(G, '__mikaTake', () => new Promise((r) => {
    if (outbox.length) r(outbox.shift()); else if (ended) r(null); else takers.push(r);
  }));
  const call = (op, args) => new Promise((resolve, reject) => {
    if (ended) { reject(new Error('script_ended')); return; }
    const id = ++seq;
    pending.set(id, { resolve, reject });
    post(stringify({ id, op, args: args || {} }));
  });
  fixed(G, '__mikaSettle', (id, r) => {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    if (r && r.ok) { p.resolve(r.value); return; }
    const e = new Error(((r && r.error) || 'failed') + (r && r.message ? ': ' + r.message : ''));
    e.code = r && r.error;
    p.reject(e);
  });
  fixed(G, '__mikaEnd', () => {
    ended = true;
    for (const p of pending.values()) p.reject(new Error('script_ended'));
    pending.clear();
    for (const t of takers.splice(0)) t(null);
  });
  const sleep = (ms) => new Promise((r) => timer(r, ms));
  const refOf = (r) => (r && typeof r === 'object') ? r.ref : r;
  const str = (x) => { if (typeof x === 'string') return x; try { return stringify(x); } catch (e) { return String(x); } };
  const mika = freeze({
    snapshot: () => call('snapshot'),
    read: (opts) => call('read', opts || {}),
    query: (selector, opts) => call('query', { selector, ...(opts || {}) }),
    queryAll: (selector, opts) => call('queryAll', { selector, ...(opts || {}) }),
    text: (selector, opts) => call('text', { selector: selector || 'body', ...(opts || {}) }),
    tap: (ref) => call('tap', { ref: refOf(ref) }),
    type: (ref, text, opts) => call('type', { ref: refOf(ref), text, enter: !!(opts && opts.enter) }),
    scroll: (direction, opts) => call('scroll', { direction, ...(opts || {}) }),
    navigate: (url) => call('navigate', { url }),
    wait: async (w) => {
      if (typeof w === 'number') { await sleep(Math.max(0, Math.min(w, $MAX_WAIT_MS))); return true; }
      if (!w || typeof w.text !== 'string' || !w.text) throw new Error('invalid_arguments: mika.wait takes milliseconds or {text, timeoutMs}');
      return call('waitText', { text: w.text, timeoutMs: w.timeoutMs });
    },
    log: (...xs) => { if (!ended) post(stringify({ id: 0, op: 'log', args: { text: xs.map(str).join(' ') } })); },
  });

  // Shadow every name on this world's global object (including the prototype chain), allowing only pure ECMAScript built-ins
  const ALLOWED = new Set(${js(ALLOWED_GLOBALS)});
  const RESERVED = new Set(${js(RESERVED)});
  const names = new Set(['globalThis', 'window', 'self', 'document', 'location', 'top', 'parent', 'frames', 'opener']);
  for (let o = G; o; o = O.getPrototypeOf(o)) for (const k of O.getOwnPropertyNames(o)) {
    if (/^[A-Za-z_$][\w$]*$/.test(k) && !ALLOWED.has(k) && !RESERVED.has(k)) names.add(k);
  }
  names.add('__mikaRun');
  const shadow = [...names];
  const blanks = shadow.map(() => undefined);
  fixed(G, '__mikaRun', (code) => {
    // Model code sits inside an inner arrow function: the names it declares (even ones matching shadowed names) live in its own scope and do not clash with the parameters
    const fn = new AsyncFunction(...shadow, 'mika', "'use strict';\nreturn (async () => {\n" + code + '\n})();\n//# sourceURL=$SOURCE_URL');
    return apply(fn, undefined, [...blanks, mika]).then((r) => (r === undefined ? null : stringify(r)));
  });
})();
//# sourceURL=$PRELUDE_URL
""".trimStart()

    /** The expression that runs model code (evaluated in the script world, awaiting the Promise). */
    fun run(code: String): String = "globalThis.__mikaRun(${JsonPrimitive(code)})"

    /**
     * import must not appear in model code (checked lexically: it is blocked wherever it occurs outside strings, comments and regexes): a dynamic import runs the module in the global scope and can reach the global object.
     * Returns a description of the problem, or null when there is none.
     */
    fun codeProblem(code: String): String? =
        if (JsSource.usesImport(code)) "import is not available in page scripts (write the word only inside strings or comments)" else null

    /**
     * The fixed reader in the read world: it reads the DOM according to `{mode, selector, fields, limit, maxChars}` and returns pure data only.
     * mode: one (the first one), all (list and total), count (how many), text (the visible text joined together), has (whether the page text contains the string [selector]).
     * Password-like values and the value attribute are masked, and on* attributes are not given out.
     */
    val READER: String = """
(() => {
  'use strict';
  if (Object.prototype.hasOwnProperty.call(globalThis, '__mikaRead')) return;
  const MASK = '$SECRET_MASK';
  const SECRET_AC = new Set(${js(SECRET_AUTOCOMPLETE)});
  const SECRET_TYPES = new Set(${js(SECRET_INPUT_TYPES)});
  const secret = (el) => {
    if (el instanceof HTMLInputElement && SECRET_TYPES.has(String(el.type).toLowerCase())) return true;
    if (!(el instanceof HTMLInputElement) && !(el instanceof HTMLTextAreaElement)) return false;
    const ac = el.getAttribute('autocomplete');
    return !!ac && ac.toLowerCase().split(/\s+/).some((t) => SECRET_AC.has(t));
  };
  const clip = (s, n) => (s.length > n ? s.slice(0, n) : s);
  const textOf = (el) => (secret(el) ? '' : String(el.innerText ?? el.textContent ?? '').trim());
  const item = (el, f, n) => {
    const o = { tag: el.tagName.toLowerCase() };
    if (f.includes('text')) o.text = clip(textOf(el), n);
    if (f.includes('attrs')) {
      o.attrs = {};
      for (const a of el.attributes) {
        const k = a.name.toLowerCase();
        if (k.startsWith('on')) continue;
        o.attrs[k] = k === 'value' && secret(el) ? (a.value ? MASK : '') : clip(a.value, n);
      }
    }
    if (f.includes('value')) {
      o.value = 'value' in el ? (secret(el) ? (el.value ? MASK : '') : clip(String(el.value), n)) : null;
      if (el instanceof HTMLInputElement && (el.type === 'checkbox' || el.type === 'radio')) o.checked = el.checked;
      if (el instanceof HTMLSelectElement) o.selected = Array.from(el.selectedOptions, (x) => clip(x.text, n));
    }
    if (f.includes('box')) {
      const r = el.getBoundingClientRect(), w = innerWidth || 1, h = innerHeight || 1;
      o.box = { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height),
        center: { x: Math.round(Math.min(999, Math.max(0, (r.x + r.width / 2) / w * 999))), y: Math.round(Math.min(999, Math.max(0, (r.y + r.height / 2) / h * 999)) ) },
        visible: r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < h && r.left < w };
    }
    return o;
  };
  Object.defineProperty(globalThis, '__mikaRead', { value: (q) => {
    if (q.mode === 'has') return { ok: true, value: String(document.body ? document.body.innerText : '').includes(q.selector) };
    let list;
    try { list = document.querySelectorAll(q.selector); } catch (e) { return { ok: false, error: 'invalid_selector', message: String(e.message) }; }
    if (q.mode === 'count') return { ok: true, value: list.length };
    if (q.mode === 'text') {
      let t = '';
      for (const el of list) { const s = textOf(el); if (s) t += (t ? '\n' : '') + s; if (t.length >= q.maxChars) break; }
      return { ok: true, value: clip(t, q.maxChars) };
    }
    const items = [];
    for (let i = 0; i < list.length && items.length < q.limit; i++) items.push(item(list[i], q.fields, q.maxChars));
    return { ok: true, value: q.mode === 'one' ? (items[0] || null) : { items, total: list.length } };
  } });
})();
""".trimStart()

    /** The expression that reads according to [spec] in the read world. */
    fun read(spec: JsonObject): String = "globalThis.__mikaRead($spec)"

    /**
     * Backstop: when a script does send a request anyway, the initiator stack of `Network.requestWillBeSent` contains the model script or the prelude.
     * The whole stack is walked (including the async parent chain).
     */
    fun requestFromScript(params: JsonObject): Boolean {
        var stack = (params["initiator"] as? JsonObject)?.get("stack") as? JsonObject
        var depth = 0
        while (stack != null && depth++ < 32) {
            val frames = stack["callFrames"] as? JsonArray
            if (frames != null && frames.any { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull in SCRIPT_URLS }) return true
            stack = stack["parent"] as? JsonObject
        }
        return false
    }

    /** Backstop: while a script is running and no mika action is in progress, a main-frame navigation - one not caused by a mika action - is always blocked. */
    fun navigationOutsideAction(params: JsonObject, mainFrameId: String, inAction: Boolean): Boolean =
        !inAction && (params["frameId"] as? JsonPrimitive)?.contentOrNull == mainFrameId

    private val SCRIPT_URLS = setOf(SOURCE_URL, PRELUDE_URL)

    /** The expression that takes the next message from the outbox (evaluated in the script world, awaiting the Promise; hands back null once it has ended). */
    const val TAKE = "globalThis.__mikaTake()"

    /** One message from the outbox; null when it is not a valid call. */
    fun parseCall(payload: String): ScriptCall? {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val id = (o["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 } ?: return null
        val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val args = o["args"] as? JsonObject ?: JsonObject(emptyMap())
        return ScriptCall(id, op, args)
    }

    /** Pass back the result of call [id]. The result is a JSON object literal and is embedded into the expression directly. */
    fun settle(id: Int, result: JsonObject): String = "globalThis.__mikaSettle && globalThis.__mikaSettle($id, $result)"

    const val END = "globalThis.__mikaEnd && globalThis.__mikaEnd()"

    private val STACK_LINE = Regex(Regex.escape(SOURCE_URL) + ":(\\d+):\\d+")

    /**
     * `Runtime.evaluate`'s exceptionDetails → (description, line number in the script). The line number is taken from the model script's position in the call stack and converted to the line the model wrote (1-based);
     * a syntax error has no such stack and reports no line number.
     */
    fun error(details: JsonObject): Pair<String, Int?> {
        val exception = details["exception"] as? JsonObject
        val description = (exception?.get("description") as? JsonPrimitive)?.contentOrNull
            ?: (exception?.get("value") as? JsonPrimitive)?.contentOrNull
        val text = (details["text"] as? JsonPrimitive)?.contentOrNull
        val message = description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: text ?: "script failed"
        val line = description?.let { STACK_LINE.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.let { it - LINE_OFFSET }?.takeIf { it >= 1 }
        return message to line
    }
}
