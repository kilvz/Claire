package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.run.record.Redactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI

const val READ_SCRIPT_DEFAULT_TIMEOUT_MS = 10_000
const val READ_SCRIPT_MAX_TIMEOUT_MS = 30_000
/** The longest a single sleep may wait. */
const val READ_SLEEP_MAX_MS = 5_000

/**
 * How read scripts (web_evaluate_js, site commands) are done: they run in an isolated world in the main frame of the current tab, can see the real DOM, but cannot touch the originals.
 * - Model code is compiled the way web_script does it: every name on this world's global object is shadowed as a parameter, leaving only pure ECMAScript built-ins and the few names given below
 *   (document, window, location, fetch, state, args, sleep, getComputedStyle, URL, URLSearchParams).
 * - The page objects handed over are all wrappers: property reads work as usual (objects read are wrapped again), while writing, deleting, defining properties and changing the prototype all report read_only;
 *   of a function only the registered read-only methods (lookup, property reads, position measurement, iteration) can be called, and everything else (click, focus, submit, DOM changes, opening windows, navigation) reports read_only.
 *   Native functions only ever receive the originals and the model only ever gets wrappers; the built-ins the model can reach are frozen first and their global names made read-only, so it cannot change them to intercept the originals passed inside wrappers or to swap out the functions the checks rely on.
 * - Reading the value of password, hidden-field, one-time-code or bank-card inputs yields `***`, and on the input's own interface only [SECRET_FIELD_PROPS] can be read as-is;
 *   the same holds inside same-origin iframes, shadow roots and templates (nodes are recognized with native getters rather than instanceof). innerHTML and the like are masked on a copy that belongs to no window, so no page code runs;
 *   when an outer element holds a secret textarea with text, textContent and the like are taken from a copy too. The child text nodes of a secret textarea and the value attribute node of a secret input read as `***`,
 *   and stay so even after they have left the page.
 *   Selecting elements by the value attribute is not allowed (the value could be guessed digit by digit); selectors are first restored by the CSS rules and then judged ([SelectorPolicy]).
 *   Selector and attribute-name arguments are turned into strings first and only once: what is checked is exactly what is handed to the browser.
 * - Read scripts do not touch the network: a fetch call reports blocked_network ([NETWORK_MESSAGE]); XMLHttpRequest, sendBeacon, WebSocket, EventSource,
 *   new Image() and the like report blocked_network when called or constructed through a wrapper; creating elements and changing src/href are page changes anyway (read_only). Only what the page already holds can be read.
 * - state is an object kept for the lifetime of this tab's read world: consecutive read scripts on the same page share it; it is gone once the page switches documents.
 * Backstops (on DevTools events): if the script sends any network request, or the main frame navigates while it runs, the read script is ended with an error.
 */
object ReadScriptProtocol {
    /** The message given when a read script tries to use the network. */
    const val NETWORK_MESSAGE = "network access is not allowed in read scripts; use web_capture to read what the page loaded"

    /** Names of page functions and constructors that can send requests: calling or constructing them through a wrapper reports blocked_network (not merely read_only), so the reason is clear. */
    val NETWORK_NAMES = listOf(
        "fetch", "sendBeacon", "XMLHttpRequest", "WebSocket", "EventSource", "Image", "Audio", "Request",
        "Worker", "SharedWorker", "RTCPeerConnection", "importScripts",
    )

    const val WORLD_PREFIX = "mika-read-"
    const val SOURCE_URL = "mika-read.js"
    const val PRELUDE_URL = "mika-read-prelude.js"
    const val MASK = PageScriptProtocol.SECRET_MASK

    /** Names model code can use besides pure ECMAScript built-ins, also in the order the parameters are passed. */
    val PROVIDED = listOf("document", "window", "location", "fetch", "state", "args", "sleep", "getComputedStyle", "URL", "URLSearchParams")

    /**
     * The read-only methods that can be called through a wrapper, by interface. Ones not found on the prototype are skipped (WebViews differ between OS versions).
     * Interfaces with Symbol.iterator additionally get their iterator allowed.
     */
    val READ_METHODS: Map<String, List<String>> = linkedMapOf(
        "Node" to listOf("contains", "hasChildNodes", "getRootNode", "compareDocumentPosition", "isEqualNode", "isSameNode", "lookupPrefix", "lookupNamespaceURI", "isDefaultNamespace"),
        "Element" to listOf(
            "querySelector", "querySelectorAll", "closest", "matches", "webkitMatchesSelector", "getAttribute", "getAttributeNS", "getAttributeNames",
            "hasAttribute", "hasAttributeNS", "hasAttributes", "getElementsByClassName", "getElementsByTagName", "getElementsByTagNameNS",
            "getBoundingClientRect", "getClientRects", "checkVisibility",
        ),
        "Document" to listOf(
            "querySelector", "querySelectorAll", "getElementById", "getElementsByClassName", "getElementsByTagName", "getElementsByTagNameNS",
            "getElementsByName", "elementFromPoint", "elementsFromPoint", "hasFocus",
        ),
        "DocumentFragment" to listOf("querySelector", "querySelectorAll", "getElementById"),
        "CharacterData" to listOf("substringData"),
        "NodeList" to listOf("item", "forEach", "entries", "keys", "values"),
        "HTMLCollection" to listOf("item", "namedItem"),
        "HTMLFormControlsCollection" to listOf("namedItem"),
        "HTMLOptionsCollection" to listOf("item", "namedItem"),
        "HTMLSelectElement" to listOf("item", "namedItem"),
        "HTMLAnchorElement" to listOf("toString"),
        "HTMLAreaElement" to listOf("toString"),
        "NamedNodeMap" to listOf("item", "getNamedItem", "getNamedItemNS"),
        "DOMTokenList" to listOf("item", "contains", "forEach", "entries", "keys", "values"),
        "CSSStyleDeclaration" to listOf("getPropertyValue", "getPropertyPriority", "item"),
        "DOMRectReadOnly" to listOf("toJSON"),
        "DOMRectList" to listOf("item"),
        "MediaQueryList" to listOf(),
    )

    /** Read-only methods on the global object itself. */
    val GLOBAL_READ_METHODS = listOf("getComputedStyle", "matchMedia")

    /** Properties that must not be read: cookies, local storage and the like (sign-in credentials often live there). */
    val DENIED_PROPS = listOf("cookie", "localStorage", "sessionStorage", "indexedDB", "caches", "cookieStore", "credentials", "serviceWorker", "clipboard", "openDatabase")

    /**
     * Properties on a secret input's own interface (HTMLInputElement, HTMLTextAreaElement) that can still be read as-is (password fields, hidden fields, one-time codes and the like): page-fixed settings that do not change with what is typed.
     * Every other property on the interface reads as `***`: valueAsNumber, valueAsDate, selectionStart / selectionEnd, textLength, validity, validationMessage,
     * and whatever browsers add later; methods still go through wrappers, and [READ_METHODS] decides whether they may be called. value, textContent and the like instead give `***` or empty depending on whether a value exists.
     */
    val SECRET_FIELD_PROPS = listOf(
        "type", "name", "form", "labels", "list", "placeholder", "autocomplete", "disabled", "readOnly", "required", "willValidate",
        "maxLength", "minLength", "size", "pattern", "min", "max", "step", "multiple", "dirName", "cols", "rows", "wrap",
        "formAction", "formEnctype", "formMethod", "formNoValidate", "formTarget",
    )

    /** Built-ins (together with their prototypes) frozen before they are handed to the model, with their global names made read-only too: the model cannot modify them, so it can neither intercept the originals passed inside wrappers nor swap out the functions the checks rely on. */
    private val FROZEN = PageScriptProtocol.ALLOWED_GLOBALS.filter { it !in setOf("undefined", "NaN", "Infinity") } + listOf("URL", "URLSearchParams")

    /** How many lines of compiled source come before line 1 of model code (`(async function anonymous(…`, `) {`, `'use strict';`, `return (` or a blank line). */
    private const val LINE_OFFSET = 4

    private fun js(v: Collection<String>): String = v.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    private fun jsMap(m: Map<String, List<String>>): String = m.entries.joinToString(",", "{", "}") { (k, v) -> JsonPrimitive(k).toString() + ":" + js(v) }

    /** The prelude installed into the read world: it freezes the built-ins, registers the callable methods, and defines the wrappers, `__mikaReadRun` and `__mikaReadStop`. Installing it again does nothing. */
    val PRELUDE: String = """
(() => {
  'use strict';
  const G = globalThis, O = Object, R = Reflect;
  if (O.prototype.hasOwnProperty.call(G, '__mikaReadRun')) return;
  const AsyncFunction = O.getPrototypeOf(async function () {}).constructor;
  const fnBind = G.Function.prototype.bind;
  const apply = R.apply, rget = R.get, ownKeys = R.ownKeys, getDesc = O.getOwnPropertyDescriptor, getProto = O.getPrototypeOf;
  const freeze = O.freeze, isArray = Array.isArray, keysOf = O.keys, define = O.defineProperty, names = O.getOwnPropertyNames;
  const stringify = JSON.stringify, parseJson = JSON.parse, timer = G.setTimeout;
  const URL_ = G.URL, loc = G.location, doc = G.document;
  const Elem = G.Element, Input = G.HTMLInputElement, Area = G.HTMLTextAreaElement;
  const MASK = '$MASK';
  const fixed = (o, k, v) => { try { define(o, k, { value: v, writable: false, enumerable: false, configurable: false }); } catch (e) {} };
  const fail = (code, message) => { const e = new Error(code + ': ' + message); e.code = code; return e; };
  const deny = (what) => { throw fail('read_only', what + ' is not allowed in a read script; it can only read the page'); };
  const NETWORK = new Set(${js(NETWORK_NAMES)});
  const offline = () => { throw fail('blocked_network', ${JsonPrimitive(NETWORK_MESSAGE)}); };

  // Seal off the paths that run strings as code (they run in the global scope) and the call-stack APIs
  const unavailable = function () { throw fail('read_only', 'running code from strings is not available'); };
  const fnKinds = [function () {}, async function () {}, function* () {}, async function* () {}];
  for (const f of fnKinds) fixed(getProto(f), 'constructor', unavailable);
  fixed(G, 'eval', unavailable);
  fixed(G, 'Function', unavailable);
  fixed(G.Error, 'prepareStackTrace', undefined);

  // Freeze the built-ins the model can reach
  const ice = (x) => { if (x && (typeof x === 'object' || typeof x === 'function')) { try { freeze(x); } catch (e) {} } };
  // Make the global names read-only too: otherwise model code could rewrite String and defeat the checks below, and the same for read scripts running later in this world
  for (const n of ${js(FROZEN)}) { const v = G[n]; ice(v); if (v && typeof v === 'function') ice(v.prototype); if (v !== undefined) fixed(G, n, v); }
  for (const f of fnKinds) ice(getProto(f));
  const arrayIter = getProto([][Symbol.iterator]());
  for (const p of [arrayIter, getProto(arrayIter), getProto(new Map().entries()), getProto(new Set().values()), getProto(''[Symbol.iterator]())]) ice(p);

  // Native functions that can be called through wrappers
  const SAFE = new WeakSet();
  const READ = ${jsMap(READ_METHODS)};
  for (const iface of keysOf(READ)) {
    const C = G[iface];
    const p = C && C.prototype;
    if (!p) continue;
    for (const k of READ[iface]) { const d = getDesc(p, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
    const it = getDesc(p, Symbol.iterator);
    if (it && typeof it.value === 'function') SAFE.add(it.value);
  }
  for (const k of ${js(GLOBAL_READ_METHODS)}) { const d = getDesc(G, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
  for (const k of ['toString', 'valueOf']) { const d = getDesc(loc, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
  for (const f of [Array.prototype.values, Array.prototype.keys, Array.prototype.entries, Array.prototype.forEach, arrayIter.next,
    O.prototype.toString, O.prototype.toLocaleString, O.prototype.valueOf, O.prototype.hasOwnProperty, O.prototype.propertyIsEnumerable]) SAFE.add(f);
  // Those whose first argument is a selector; and which argument is the attribute name
  const SELECTORS = new WeakSet();
  for (const iface of ['Element', 'Document', 'DocumentFragment']) {
    const p = G[iface] && G[iface].prototype;
    if (p) for (const k of ['querySelector', 'querySelectorAll', 'closest', 'matches', 'webkitMatchesSelector']) { const d = getDesc(p, k); if (d && typeof d.value === 'function') SELECTORS.add(d.value); }
  }
  const ATTRS = new WeakMap();
  for (const [k, i] of [['getAttribute', 0], ['getAttributeNS', 1]]) { const d = Elem && getDesc(Elem.prototype, k); if (d && typeof d.value === 'function') ATTRS.set(d.value, i); }
${SelectorPolicy.JS}  const DENIED = new Set(${js(DENIED_PROPS)});

  // Inputs whose value is not read: password fields, hidden fields, and those whose autocomplete marks them as a password, a bank card or a one-time code
  const SECRET_AC = new Set(${js(PageScriptProtocol.SECRET_AUTOCOMPLETE)});
  const SECRET_TYPES = new Set(${js(PageScriptProtocol.SECRET_INPUT_TYPES)});
  // A node's kind is read with the native getter rather than instanceof: nodes inside a same-origin iframe belong to that frame's own set of interfaces, so instanceof fails to recognize them while the native getter still does
  const getterOf = (C, k) => { const d = C && getDesc(C.prototype, k); return d && d.get; };
  const nodeTypeOf = getterOf(G.Node, 'nodeType'), localNameOf = getterOf(Elem, 'localName'), shadowHostOf = getterOf(G.ShadowRoot, 'host');
  const native = (g, x) => { if (!g || x === null || typeof x !== 'object') return undefined; try { return apply(g, x, []); } catch (e) { return undefined; } };
  // The node type is remembered per object (an object's type never changes): reading a property mostly needs one type check first, and on objects that are not nodes (collections, rectangles, styles and the like)
  // the native nodeType getter throws every time, so each object is checked only once
  const kinds = new WeakMap();
  const typeOf = (x) => {
    if (x === null || typeof x !== 'object') return undefined;
    let t = kinds.get(x);
    if (t === undefined) { t = native(nodeTypeOf, x) || 0; kinds.set(x, t); }
    return t || undefined;
  };
  const tagOf = (x) => (typeOf(x) === 1 ? native(localNameOf, x) : undefined);
  const secretNow = (el) => {
    const tag = tagOf(el);
    if (tag === 'input') {
      if (SECRET_TYPES.has(String(el.type).toLowerCase())) return true;
    } else if (tag !== 'textarea') return false;
    const ac = el.getAttribute('autocomplete');
    return !!ac && ac.toLowerCase().split(/\s+/).some((t) => SECRET_AC.has(t));
  };
  // Anything that once was a secret input (e.g. a "show password" toggle turning type into text) keeps counting as secret: it is watched from the moment the read world is created
  const wasSecret = new WeakSet();
  const markIn = (root) => { try { if (secretNow(root)) wasSecret.add(root); for (const x of root.querySelectorAll('input, textarea')) if (secretNow(x)) wasSecret.add(x); } catch (e) {} };
  try {
    new G.MutationObserver((records) => {
      for (const r of records) {
        if (r.type === 'attributes') { if (secretNow(r.target)) wasSecret.add(r.target); } else for (const n of r.addedNodes) if (n.nodeType === 1) markIn(n);
      }
    }).observe(doc, { subtree: true, childList: true, attributes: true, attributeFilter: ['type', 'autocomplete'] });
  } catch (e) {}
  markIn(doc);
  const secret = (el) => secretNow(el) || (!!el && typeof el === 'object' && wasSecret.has(el));
  const SECRET_TEXT = new Set(['value', 'defaultValue', 'textContent', 'innerText', 'outerText', 'innerHTML', 'nodeValue']);
  // Properties on a secret input's own interface: only page-fixed settings that do not change with content are read as-is, everything else (including value-reading interfaces browsers add later) is ***
  const FIELD_SAFE = new Set(${js(SECRET_FIELD_PROPS)});
  const fieldApi = (el, k) => { const tag = tagOf(el); return !!((tag === 'input' && Input && getDesc(Input.prototype, k)) || (tag === 'textarea' && Area && getDesc(Area.prototype, k))); };
  // The input fields that innerHTML / outerHTML would serialize: template contents are serialized too, but querySelectorAll cannot look inside them, so they are searched separately.
  // Originals and copies are listed in the same order. Not recursive (templates can nest many levels deep); only HTML templates have content (the same-named element in SVG does not);
  // lookups use native methods (a form control named querySelectorAll is still found)
  const contentOf = getterOf(G.HTMLTemplateElement, 'content');
  const QSA = { 1: Elem && Elem.prototype.querySelectorAll, 9: G.Document && G.Document.prototype.querySelectorAll, 11: G.DocumentFragment && G.DocumentFragment.prototype.querySelectorAll };
  const fields = (node, out) => {
    const todo = [node];
    const inner = (t) => { const c = tagOf(t) === 'template' && native(contentOf, t); if (c) todo.push(c); };
    for (let i = 0; i < todo.length; i++) {
      const n = todo[i], find = QSA[typeOf(n)];
      inner(n);
      if (find) for (const x of apply(find, n, ['input, textarea, template'])) { if (tagOf(x) === 'template') inner(x); else out.push(x); }
    }
    return out;
  };
  // When the HTML carries the value of a secret input, mask it on a copy and read from there. Which fields get masked is decided by the originals (the copy has no record of "used to be a secret input"), and the fields at the same positions in the copy are masked.
  // The copy lives in an empty document belonging to no window (created once and reused): custom elements are not constructed, no page code runs and no resources load. A ShadowRoot cannot be copied, so its children are put into a div.
  // The serialization this produces differs from the page's own in two ways that do not affect masking: content inside noscript is escaped; XHTML pages come out in HTML form
  let inert = null;
  // The masked copy; null when v holds no secret input, and MASK when the copy and the original do not line up
  const masked = (v) => {
    const root = typeOf(v) !== 1;
    const orig = fields(v, root ? [] : [v]);
    let hit = false;
    for (const x of orig) if (secret(x)) { hit = true; break; }
    if (!hit) return null;
    const d = inert || (inert = doc.implementation.createHTMLDocument(''));
    let c;
    if (root) { c = d.createElement('div'); for (const n of v.childNodes) c.appendChild(d.importNode(n, true)); } else c = d.importNode(v, true);
    const copy = fields(c, root ? [] : [c]);
    if (copy.length !== orig.length) return MASK;
    for (let i = 0; i < copy.length; i++) {
      if (!secret(orig[i])) continue;
      const x = copy[i];
      if (x.hasAttribute('value')) x.setAttribute('value', MASK);
      if (tagOf(x) === 'textarea' && x.textContent) x.textContent = MASK;
    }
    return c;
  };
  const html = (v, k) => { const c = masked(v); return c === null ? rget(v, k, v) : c === MASK ? MASK : rget(c, k, c); };
  // The text inside a secret textarea is its initial value (this also covers the page writing what was typed back into the initial value). It shows through the textContent of outer elements and of a shadow root;
  // so does the innerText of an outer element that is not rendered (there innerText is textContent); when the textarea sits inside an option, the rendered innerText carries it too
  // (an option serializes by its text); the text of a and option, the label and value of option, and the value and defaultValue of output likewise count the text of their descendants.
  // These are masked on a copy before being read. The rendered innerText of other elements does not contain the textarea's text and is given as-is
  const textOf = getterOf(G.Node, 'textContent'), parentOf = getterOf(G.Node, 'parentNode'), closest = Elem && Elem.prototype.closest;
  // A Map is used because property names can be constructor, __proto__ and the like
  const DESC = new Map([['text', new Set(['a', 'option'])], ['label', new Set(['option'])], ['value', new Set(['option', 'output'])], ['defaultValue', new Set(['output'])]]);
  // A secret textarea with text inside v: 0 when there is none, 1 when there is one, 2 when one sits inside an option
  const areasIn = (v) => {
    const find = QSA[typeOf(v)];
    let hit = 0;
    if (find) for (const a of apply(find, v, ['textarea'])) if (secret(a) && native(textOf, a)) { hit = 1; if (closest && apply(closest, a, ['option'])) return 2; }
    return hit;
  };
  const text = (v, k) => {
    const x = rget(v, k, v);
    if (!x || typeof x !== 'string') return x;
    const hit = areasIn(v), shown = k === 'innerText' || k === 'outerText';
    if (!hit || (shown && hit === 1 && x !== native(textOf, v))) return x;
    const c = masked(v);
    return c === null ? x : c === MASK ? MASK : DESC.has(k) ? rget(c, k, c) : native(textOf, c);
  };
  // Secret text nodes once handed out are remembered and keep counting as secret: the child text nodes of a secret textarea (in XML pages these may be CDATA sections, which are text nodes too and count towards the value),
  // and the value attribute node of a secret input. When the page replaces the textarea's text (writing what was typed back into the initial value) or removes the value attribute, the old node leaves the document but still carries the text.
  // The field a node belonged to is remembered as well: when the field was not secret at the time it was handed to the model, became secret later, the text went into the same node, and the node is no longer in that field (it left the document
  // or was moved into another element), it is judged by that field
  const held = new WeakSet(), ownerOf = new WeakMap();
  const HELD_TEXT = new Set(['data', 'nodeValue', 'textContent', 'wholeText', 'length', 'value']);
  const substringData = G.CharacterData && G.CharacterData.prototype.substringData;
  const holds = (v) => {
    if (held.has(v)) return true;
    const t = typeOf(v);
    let p;
    if (t === 3 || t === 4) { p = native(parentOf, v); if (tagOf(p) !== 'textarea') p = undefined; }
    else if (t === 2) { if (String(v.name).toLowerCase() !== 'value') return false; p = v.ownerElement; }
    else return false;
    if (p) ownerOf.set(v, p); else p = ownerOf.get(v);
    if (!p || !secret(p)) return false;
    held.add(v);
    return true;
  };
  const readProp = (v, k) => {
    if (typeof k === 'string') {
      if (DENIED.has(k)) deny('reading ' + k);
      if ((k === 'outerHTML' || k === 'innerHTML') && (typeOf(v) === 1 || (k === 'innerHTML' && native(shadowHostOf, v) !== undefined))) return html(v, k);
      if (SECRET_TEXT.has(k) && secret(v)) { const x = rget(v, k, v); return x ? MASK : x; }
      if (!FIELD_SAFE.has(k) && fieldApi(v, k) && secret(v)) { const x = rget(v, k, v); return typeof x === 'function' ? x : MASK; }
      if (HELD_TEXT.has(k) && holds(v)) { const x = rget(v, k, v); return x ? MASK : x; }
      if (k === 'textContent' || k === 'innerText' || k === 'outerText') return text(v, k);
      const tags = DESC.get(k);
      if (tags !== undefined) {
        const tag = tagOf(v);
        // A select's value is the value of the option selected on the page (the one selected in the copy is not necessarily that)
        if (tag === 'select' && k === 'value' && areasIn(v)) { const i = rget(v, 'selectedIndex', v); return i < 0 ? '' : readProp(rget(v, 'options', v)[i], k); }
        if (tags.has(tag)) return text(v, k);
      }
    }
    return rget(v, k, v);
  };

  // Wrappers: the model only ever holds wrappers, and originals only go to native functions
  const toProxy = new WeakMap(), toReal = new WeakMap();
  const wrap = (v, depth) => {
    if (v === null || (typeof v !== 'object' && typeof v !== 'function')) return v;
    if (toReal.has(v)) return v;
    const d = (depth || 0) + 1;
    if (d <= 4 && isArray(v)) { const out = []; for (let i = 0; i < v.length; i++) out.push(wrap(v[i], d)); return out; }
    if (d <= 4 && typeof v === 'object') {
      const proto = getProto(v);
      if (proto === O.prototype || proto === null) {
        const out = {};
        for (const k of keysOf(v)) define(out, k, { value: wrap(v[k], d), enumerable: true, writable: true, configurable: true });
        return out;
      }
    }
    let p = toProxy.get(v);
    if (!p) { holds(v); p = makeProxy(v); toProxy.set(v, p); toReal.set(p, v); }
    return p;
  };
  const toNative = (a) => {
    if (a === null || (typeof a !== 'object' && typeof a !== 'function')) return a;
    const r = toReal.get(a);
    // A wrapped function is handed out as-is: whoever calls it still has to pass the checks
    if (r !== undefined) return typeof r === 'function' ? a : r;
    // The model's own callbacks: whatever a native function passes to them is wrapped first
    if (typeof a === 'function') return function () { const xs = []; for (let i = 0; i < arguments.length; i++) xs.push(wrap(arguments[i])); return toNative(apply(a, undefined, xs)); };
    return a;
  };
  const nameOf = (f) => { try { return String(f.name || ''); } catch (e) { return ''; } };
  const call = (f, self, xs) => {
    if (!SAFE.has(f)) { if (NETWORK.has(nameOf(f))) offline(); deny('calling ' + (nameOf(f) || 'this function')); }
    const t = toNative(self);
    if (f === substringData && holds(t)) return MASK;
    const args = [];
    for (const x of xs) args.push(toNative(x));
    // Selector and attribute-name arguments are turned into strings first and only once: an object's toString may give a different one each time, and what is checked must be exactly what is handed to the browser
    if (SELECTORS.has(f) && args.length) {
      args[0] = String(args[0]);
      if (selectsByValue(args[0])) throw fail('invalid_selector', 'selecting by the value attribute is not allowed; read the value property instead');
    }
    const at = ATTRS.get(f);
    if (at !== undefined && at < args.length) args[at] = String(args[at]);
    if (at !== undefined && secret(t) && String(args[at]).toLowerCase() === 'value') { const x = apply(f, t, args); return x ? MASK : x; }
    return wrap(apply(f, t, args));
  };
  const changing = () => deny('changing the page');
  // A function wrapper is built on a bound plain function: it can be new-ed (so construct is reached and the reason is reported clearly) and has no prototype of its own (so it does not trip the proxy invariants)
  const makeProxy = (v) => new Proxy(typeof v === 'function' ? apply(fnBind, function () {}, [undefined]) : {}, {
    get: (_, k) => wrap(readProp(v, k)),
    set: changing, defineProperty: changing, deleteProperty: changing, setPrototypeOf: changing, preventExtensions: changing,
    getPrototypeOf: () => null,
    has: (_, k) => k in v,
    ownKeys: () => { const out = []; for (const k of ownKeys(v)) if (!(typeof k === 'string' && DENIED.has(k))) out.push(k); return out; },
    getOwnPropertyDescriptor: (_, k) => {
      if (typeof k === 'string' && DENIED.has(k)) return undefined;
      const d = getDesc(v, k);
      return d ? { value: wrap(readProp(v, k)), writable: false, enumerable: d.enumerable, configurable: true } : undefined;
    },
    apply: (_, self, xs) => call(v, self, xs),
    construct: () => { if (typeof v === 'function' && NETWORK.has(nameOf(v))) offline(); deny('creating page objects'); },
  });

  // Names available to model code: pure ECMAScript built-ins + PROVIDED, with every other global name shadowed
  const PROVIDED = ${js(PROVIDED)};
  const ALLOWED = new Set(${js(PageScriptProtocol.ALLOWED_GLOBALS)}.concat(PROVIDED));
  const RESERVED = new Set(${js(PageScriptProtocol.RESERVED)});
  const hidden = new Set(['globalThis', 'self', 'top', 'parent', 'frames', 'opener', '__mikaReadRun', '__mikaReadStop']);
  for (let o = G; o; o = getProto(o)) for (const k of names(o)) {
    if (/^[A-Za-z_$][\w$]*$/.test(k) && !ALLOWED.has(k) && !RESERVED.has(k)) hidden.add(k);
  }
  const shadow = [...hidden];
  const blanks = shadow.map(() => undefined);
  const params = shadow.concat(PROVIDED);
  const state = {};
  const pageWindow = wrap(G), pageDocument = wrap(doc), pageLocation = wrap(loc), computedStyle = wrap(G.getComputedStyle);
  let generation = 0;
  const STACK_LINE = /mika-read\.js:(\d+):\d+/;
  const KNOWN = new Set(['read_only', 'invalid_selector', 'blocked_network', 'limit_exceeded', 'network_error', 'too_large', 'invalid_arguments', 'script_ended']);
  const failure = (e) => {
    const m = STACK_LINE.exec(String((e && e.stack) || ''));
    const line = m ? Number(m[1]) - $LINE_OFFSET : 0;
    const code = e && typeof e.code === 'string' && KNOWN.has(e.code) ? e.code : 'script_error';
    return { ok: false, error: code, message: String((e && e.message) || e), line: line > 0 ? line : null };
  };

  fixed(G, '__mikaReadStop', () => { generation++; });
  fixed(G, '__mikaReadRun', (code, args, lim) => {
    const gen = ++generation;
    const live = () => { if (gen !== generation) throw fail('script_ended', 'the script was stopped'); };
    // No network: for the data behind the page, read the responses the page itself received (web_capture)
    const pageFetch = () => { live(); offline(); };
    const sleep = (ms) => { live(); return new Promise((done) => timer(done, Math.max(0, Math.min(Number(ms) || 0, lim.sleepMs)))); };
    let fn;
    try {
      fn = new AsyncFunction(...params, "'use strict';\nreturn (\n" + code + '\n);\n//# sourceURL=$SOURCE_URL');
    } catch (e) {
      try {
        fn = new AsyncFunction(...params, "'use strict';\n\n" + code + '\n//# sourceURL=$SOURCE_URL');
      } catch (e2) {
        return Promise.resolve(failure(e2));
      }
    }
    const input = args == null ? undefined : parseJson(stringify(args));
    const ice2 = (x) => { if (x && typeof x === 'object') { for (const k of keysOf(x)) ice2(x[k]); freeze(x); } return x; };
    let running;
    try {
      running = apply(fn, undefined, blanks.concat([pageDocument, pageWindow, pageLocation, pageFetch, state, ice2(input), sleep, computedStyle, URL_, G.URLSearchParams]));
    } catch (e) {
      return Promise.resolve(failure(e));
    }
    return running.then((v) => {
      let s;
      try { s = v === undefined ? 'null' : stringify(v); } catch (e) { return { ok: false, error: 'script_error', message: 'the result is not JSON-serializable: ' + String((e && e.message) || e), line: null }; }
      if (s === undefined) s = 'null';
      const max = lim.resultChars;
      return { ok: true, json: s.length > max ? s.slice(0, max) : s, truncated: s.length > max };
    }, failure);
  });
})();
//# sourceURL=$PRELUDE_URL
""".trimStart()

    /** The expression that runs a read script once (evaluated in the read world, awaiting the Promise, returning by value). [args] are the arguments of a site command. */
    fun run(code: String, args: JsonObject?, resultChars: Int): String =
        "globalThis.__mikaReadRun(${JsonPrimitive(code)}, ${args ?: "null"}, " +
            "{resultChars: $resultChars, sleepMs: $READ_SLEEP_MAX_MS})"

    /** Once the time limit is reached, makes every later sleep of a still-running script fail. */
    const val STOP = "globalThis.__mikaReadStop && globalThis.__mikaReadStop()"

    /** Returns the page's origin (location.origin) when the read world is installed, null when it is not. */
    const val PROBE = "typeof globalThis.__mikaReadRun === 'function' ? String(location.origin) : null"

    /** The initiator stack (including the async parent chain) contains the read script or its prelude. */
    fun fromScript(params: JsonObject): Boolean {
        var stack = (params["initiator"] as? JsonObject)?.get("stack") as? JsonObject
        var depth = 0
        while (stack != null && depth++ < 32) {
            val frames = stack["callFrames"] as? JsonArray
            if (frames != null && frames.any { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull in SCRIPT_URLS }) return true
            stack = stack["parent"] as? JsonObject
        }
        return false
    }

    private val SCRIPT_URLS = setOf(SOURCE_URL, PRELUDE_URL)

    /**
     * Backstop: returns a description when the request in `Network.requestWillBeSent` was sent by the read script (or its prelude): read scripts do not touch the network, so no kind of request is allowed.
     * Null when the request did not come from the read script.
     */
    fun requestViolation(params: JsonObject): String? {
        if (!fromScript(params)) return null
        val request = params["request"] as? JsonObject
        val url = (request?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val method = (request?.get("method") as? JsonPrimitive)?.contentOrNull ?: "GET"
        return "the script sent a $method request (${url.take(200)}); $NETWORK_MESSAGE"
    }

    /** `scheme://host[:port]`, with the default port omitted; null when it cannot be parsed. */
    fun originOf(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val port = u.port.takeIf { it >= 0 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    /**
     * The object `__mikaReadRun` returns by value → the result. The result is first redacted by the web-data rules (fields with credential names, JWTs, long mixed strings, keys), then cut to [EVAL_RESULT_MAX_CHARS].
     * [fresh]: the read world was created this time (whatever was stored in state before is gone).
     */
    fun result(value: JsonObject?, fresh: Boolean): EvalJs {
        value ?: return EvalJs.Failed("script_error", "the script produced no readable result")
        fun str(k: String) = (value[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((value["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            val line = (value["line"] as? JsonPrimitive)?.intOrNull
            return EvalJs.Failed(str("error") ?: "script_error", (line?.let { "line $it: " } ?: "") + str("message").orEmpty())
        }
        val json = Redactor.pageData(str("json") ?: "null")
        val truncated = (value["truncated"] as? JsonPrimitive)?.booleanOrNull == true || json.length > EVAL_RESULT_MAX_CHARS
        return EvalJs.Ok(json.take(EVAL_RESULT_MAX_CHARS), truncated, fresh)
    }
}
