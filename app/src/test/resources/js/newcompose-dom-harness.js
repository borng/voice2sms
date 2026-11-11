'use strict';
/*
 * Test-only harness (NewComposeChipDomTest, H3 / R10a): loads app/src/main/assets/inject.js into a
 * node vm context with a small fake DOM built from a fixture HTML string, evaluates one expression
 * and prints its JSON. No network, no device. The fake DOM supports what page code commonly uses:
 * querySelector(All) with tag/#id/.class/[attr], [attr=|*=|^=|$=|~=|-=v (i)], :not(), :scope,
 * descendant/child/sibling combinators and selector lists; closest/matches; textContent/innerText;
 * getAttribute/hasAttribute; children/parentElement. An unsupported selector throws, so the test
 * fails with that selector named instead of silently matching nothing.
 *
 * Usage: node newcompose-dom-harness.js <inject.js> <case.json>
 *   case.json = {"html": "...", "search": "?itemId=...", "expr": "_v2sSlNewComposeChip()"}
 */
const fs = require('fs');
const vm = require('vm');

const injectPath = process.argv[2];
const testCase = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));

// ------------------------------------------------------------------------------ selector engine

function splitTop(s, sepChar) {
    const out = [];
    let depth = 0, quote = null, cur = '';
    for (let i = 0; i < s.length; i++) {
        const ch = s[i];
        if (quote) {
            cur += ch;
            if (ch === quote) quote = null;
            continue;
        }
        if (ch === '"' || ch === "'") { quote = ch; cur += ch; continue; }
        if (ch === '(' || ch === '[') depth++;
        if (ch === ')' || ch === ']') depth--;
        if (ch === sepChar && depth === 0) { out.push(cur); cur = ''; continue; }
        cur += ch;
    }
    out.push(cur);
    return out;
}

function parseCompound(src, whole) {
    const c = { tag: null, ids: [], classes: [], attrs: [], nots: [], scope: false, pseudo: [] };
    let i = 0;
    const ident = () => {
        const m = /^[\w-]+/.exec(src.slice(i));
        if (!m) throw new Error('unsupported selector: ' + whole);
        i += m[0].length;
        return m[0];
    };
    if (src[i] === '*') i++;
    else if (/[\w-]/.test(src[i] || '')) c.tag = ident().toLowerCase();
    while (i < src.length) {
        const ch = src[i];
        if (ch === '.') { i++; c.classes.push(ident()); }
        else if (ch === '#') { i++; c.ids.push(ident()); }
        else if (ch === '[') {
            let depth = 0, j = i, quote = null;
            for (; j < src.length; j++) {
                const cj = src[j];
                if (quote) { if (cj === quote) quote = null; continue; }
                if (cj === '"' || cj === "'") { quote = cj; continue; }
                if (cj === '[') depth++;
                if (cj === ']' && --depth === 0) break;
            }
            const body = src.slice(i + 1, j).trim();
            i = j + 1;
            const m = /^([\w:-]+)\s*(?:([*^$~|]?=)\s*(?:"([^"]*)"|'([^']*)'|([^\s\]]+))\s*(i|s)?)?$/.exec(body);
            if (!m) throw new Error('unsupported selector: ' + whole);
            const val = m[3] !== undefined ? m[3] : (m[4] !== undefined ? m[4] : m[5]);
            c.attrs.push({ name: m[1].toLowerCase(), op: m[2] || null, value: val, ci: m[6] === 'i' });
        } else if (ch === ':') {
            const m = /^:([\w-]+)(\()?/.exec(src.slice(i));
            if (!m) throw new Error('unsupported selector: ' + whole);
            i += m[0].length;
            let arg = null;
            if (m[2]) {
                let depth = 1, j = i;
                for (; j < src.length && depth; j++) {
                    if (src[j] === '(') depth++;
                    if (src[j] === ')') depth--;
                }
                arg = src.slice(i, j - 1);
                i = j;
            }
            const name = m[1];
            if (name === 'not') c.nots.push(parseList(arg));
            else if (name === 'scope') c.scope = true;
            else if (name === 'is' || name === 'where') c.pseudo.push({ is: parseList(arg) });
            else if (['first-child', 'last-child', 'only-child', 'first-of-type', 'last-of-type'].includes(name)) c.pseudo.push({ name });
            else throw new Error('unsupported selector: ' + whole);
        } else throw new Error('unsupported selector: ' + whole);
    }
    return c;
}

function parseComplex(src) {
    const parts = [];
    let s = src.trim(), i = 0, cur = '', depth = 0, quote = null;
    const pushCur = () => { if (cur) parts.push({ compound: parseCompound(cur, src) }); cur = ''; };
    while (i < s.length) {
        const ch = s[i];
        if (quote) { cur += ch; if (ch === quote) quote = null; i++; continue; }
        if (ch === '"' || ch === "'") { quote = ch; cur += ch; i++; continue; }
        if (ch === '(' || ch === '[') depth++;
        if (ch === ')' || ch === ']') depth--;
        if (depth === 0 && (ch === '>' || ch === '+' || ch === '~' || /\s/.test(ch))) {
            let j = i;
            while (j < s.length && /\s/.test(s[j])) j++;
            let comb = ' ';
            if ('>+~'.includes(s[j])) { comb = s[j]; j++; while (j < s.length && /\s/.test(s[j])) j++; }
            pushCur();
            if (parts.length === 0) {
                if (comb === ' ') { i = j; continue; }
                parts.push({ compound: parseCompound(':scope', src) });
            }
            parts.push({ comb });
            i = j;
            continue;
        }
        cur += ch;
        i++;
    }
    pushCur();
    if (!parts.length || parts[parts.length - 1].comb) throw new Error('unsupported selector: ' + src);
    return parts;
}

function parseList(src) {
    if (typeof src !== 'string' || !src.trim()) throw new Error('unsupported selector: ' + src);
    return splitTop(src, ',').map(parseComplex);
}

function attrMatch(el, a) {
    let v = el.getAttribute(a.name);
    if (v === null) return false;
    if (!a.op) return true;
    let want = a.value;
    if (a.ci) { v = v.toLowerCase(); want = want.toLowerCase(); }
    switch (a.op) {
        case '=': return v === want;
        case '*=': return want !== '' && v.indexOf(want) >= 0;
        case '^=': return want !== '' && v.startsWith(want);
        case '$=': return want !== '' && v.endsWith(want);
        case '~=': return v.split(/\s+/).includes(want);
        case '|=': return v === want || v.startsWith(want + '-');
    }
    return false;
}

function compoundMatch(el, c, scopeEl) {
    if (!el || el.nodeType !== 1) return false;
    if (c.tag && el.localName !== c.tag) return false;
    for (const id of c.ids) if (el.getAttribute('id') !== id) return false;
    const cls = (el.getAttribute('class') || '').split(/\s+/);
    for (const k of c.classes) if (!cls.includes(k)) return false;
    for (const a of c.attrs) if (!attrMatch(el, a)) return false;
    if (c.scope && el !== scopeEl) return false;
    for (const n of c.nots) if (listMatch(el, n, scopeEl)) return false;
    for (const p of c.pseudo) {
        if (p.is) { if (!listMatch(el, p.is, scopeEl)) return false; continue; }
        const sibs = el.parentNode ? el.parentNode.children : [el];
        const same = sibs.filter(x => x.localName === el.localName);
        if (p.name === 'first-child' && sibs[0] !== el) return false;
        if (p.name === 'last-child' && sibs[sibs.length - 1] !== el) return false;
        if (p.name === 'only-child' && sibs.length !== 1) return false;
        if (p.name === 'first-of-type' && same[0] !== el) return false;
        if (p.name === 'last-of-type' && same[same.length - 1] !== el) return false;
    }
    return true;
}

function complexMatch(el, parts, idx, scopeEl) {
    if (!compoundMatch(el, parts[idx].compound, scopeEl)) return false;
    if (idx === 0) return true;
    const comb = parts[idx - 1].comb;
    const prev = idx - 2;
    if (comb === '>') return complexMatch(el.parentElement, parts, prev, scopeEl);
    if (comb === ' ') {
        for (let p = el.parentElement; p; p = p.parentElement) if (complexMatch(p, parts, prev, scopeEl)) return true;
        return false;
    }
    if (comb === '+') return complexMatch(el.previousElementSibling, parts, prev, scopeEl);
    if (comb === '~') {
        for (let p = el.previousElementSibling; p; p = p.previousElementSibling) if (complexMatch(p, parts, prev, scopeEl)) return true;
        return false;
    }
    return false;
}

function listMatch(el, list, scopeEl) {
    for (const parts of list) if (complexMatch(el, parts, parts.length - 1, scopeEl)) return true;
    return false;
}

// ------------------------------------------------------------------------------------ fake DOM

function nodeList(arr) {
    arr.item = i => arr[i] || null;
    return arr;
}

class FakeText {
    constructor(text) { this.nodeType = 3; this.nodeName = '#text'; this.data = text; this.parentNode = null; }
    get textContent() { return this.data; }
    get nodeValue() { return this.data; }
    get parentElement() { return this.parentNode && this.parentNode.nodeType === 1 ? this.parentNode : null; }
}

class FakeElement {
    constructor(tag, attrs) {
        this.nodeType = 1;
        this.localName = tag.toLowerCase();
        this.tagName = this.localName.toUpperCase();
        this.nodeName = this.tagName;
        this._attrs = Object.assign({}, attrs || {});
        this.childNodes = [];
        this.parentNode = null;
        this.style = {};
        this.value = this._attrs.value || '';
        this.dataset = {};
        this.shadowRoot = null;
        this.isConnected = true;
    }
    append(n) { n.parentNode = this; this.childNodes.push(n); return n; }
    appendChild(n) { return this.append(n); }
    get children() { return nodeList(this.childNodes.filter(n => n.nodeType === 1)); }
    get childElementCount() { return this.children.length; }
    get firstElementChild() { return this.children[0] || null; }
    get lastElementChild() { const c = this.children; return c[c.length - 1] || null; }
    get parentElement() { return this.parentNode && this.parentNode.nodeType === 1 ? this.parentNode : null; }
    get nextElementSibling() {
        if (!this.parentNode) return null;
        const s = this.parentNode.children; return s[s.indexOf(this) + 1] || null;
    }
    get previousElementSibling() {
        if (!this.parentNode) return null;
        const s = this.parentNode.children; return s[s.indexOf(this) - 1] || null;
    }
    get firstChild() { return this.childNodes[0] || null; }
    get textContent() { return this.childNodes.map(n => n.textContent).join(''); }
    get innerText() { return this.textContent; }
    get innerHTML() { return ''; }
    get id() { return this._attrs.id || ''; }
    get className() { return this._attrs['class'] || ''; }
    get classList() {
        const l = this.className.split(/\s+/).filter(Boolean);
        return { contains: c => l.includes(c), length: l.length, item: i => l[i] || null, forEach: f => l.forEach(f) };
    }
    get placeholder() { return this._attrs.placeholder || ''; }
    get type() { return this._attrs.type || (this.localName === 'input' ? 'text' : ''); }
    get disabled() { return this.hasAttribute('disabled'); }
    get attributes() { return Object.keys(this._attrs).map(k => ({ name: k, value: this._attrs[k] })); }
    getAttribute(n) {
        n = String(n).toLowerCase();
        return Object.prototype.hasOwnProperty.call(this._attrs, n) ? this._attrs[n] : null;
    }
    hasAttribute(n) { return this.getAttribute(n) !== null; }
    setAttribute(n, v) { this._attrs[String(n).toLowerCase()] = String(v); }
    removeAttribute(n) { delete this._attrs[String(n).toLowerCase()]; }
    matches(sel) { return listMatch(this, parseList(sel), this); }
    closest(sel) {
        const list = parseList(sel);
        for (let e = this; e && e.nodeType === 1; e = e.parentElement) if (listMatch(e, list, this)) return e;
        return null;
    }
    querySelectorAll(sel) {
        const list = parseList(sel);
        const out = [];
        const scope = this.nodeType === 9 ? null : this;
        const walk = n => {
            for (const ch of n.childNodes) {
                if (ch.nodeType !== 1) continue;
                if (listMatch(ch, list, scope)) out.push(ch);
                walk(ch);
            }
        };
        walk(this);
        return nodeList(out);
    }
    querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
    getElementsByTagName(t) { return this.querySelectorAll(t === '*' ? '*' : t); }
    getElementsByClassName(c) { return this.querySelectorAll(c.split(/\s+/).filter(Boolean).map(x => '.' + x).join('')); }
    contains(n) { for (let e = n; e; e = e.parentNode) if (e === this) return true; return false; }
    getBoundingClientRect() { return { top: 0, left: 0, right: 100, bottom: 20, width: 100, height: 20, x: 0, y: 0 }; }
    getClientRects() { return nodeList([this.getBoundingClientRect()]); }
    get offsetParent() { return this.parentElement; }
    get offsetWidth() { return 100; }
    get offsetHeight() { return 20; }
    get clientWidth() { return 100; }
    get clientHeight() { return 20; }
    addEventListener() {}
    removeEventListener() {}
    dispatchEvent() { return true; }
    focus() {}
    blur() {}
    click() {}
    scrollIntoView() {}
}

const VOID = new Set(['input', 'img', 'br', 'hr', 'meta', 'link']);

function decode(s) {
    return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&');
}

function parseHtml(html, root) {
    const stack = [root];
    const re = /<\/([a-zA-Z][\w-]*)\s*>|<([a-zA-Z][\w-]*)((?:\s+[\w:-]+(?:\s*=\s*"[^"]*")?)*)\s*(\/?)>|([^<]+)/g;
    let m;
    while ((m = re.exec(html))) {
        const top = stack[stack.length - 1];
        if (m[1]) {
            const name = m[1].toLowerCase();
            if (top.localName !== name) throw new Error('fixture HTML: </' + name + '> closes <' + top.localName + '>');
            stack.pop();
        } else if (m[2]) {
            const attrs = {};
            const ar = /([\w:-]+)(?:\s*=\s*"([^"]*)")?/g;
            let a;
            while ((a = ar.exec(m[3] || ''))) attrs[a[1].toLowerCase()] = a[2] === undefined ? '' : decode(a[2]);
            const el = top.append(new FakeElement(m[2], attrs));
            if (!m[4] && !VOID.has(el.localName)) stack.push(el);
        } else if (m[5] && m[5].trim()) {
            top.append(new FakeText(decode(m[5].trim())));
        }
    }
    if (stack.length !== 1) throw new Error('fixture HTML: unclosed <' + stack[stack.length - 1].localName + '>');
}

const document = new FakeElement('#document', {});
document.nodeType = 9;
document.nodeName = '#document';
parseHtml(testCase.html, document);
Object.defineProperty(document, 'documentElement', { get: () => document.children[0] || null });
Object.defineProperty(document, 'body', { get: () => document.querySelector('body') });
Object.defineProperty(document, 'head', { get: () => document.querySelector('head') });
document.getElementById = id => document.querySelectorAll('*').filter(e => e.id === id)[0] || null;
document.activeElement = null;
document.readyState = 'complete';
document.visibilityState = 'visible';
document.hidden = false;
document.createElement = t => new FakeElement(t, {});
document.createTextNode = t => new FakeText(t);
document.hasFocus = () => true;

// ------------------------------------------------------------------------------------- context

const search = testCase.search || '';
const noop = () => {};
const sandbox = {
    console: { log: noop, warn: noop, error: noop, info: noop, debug: noop },
    setTimeout: () => 0, clearTimeout: noop, setInterval: () => 0, clearInterval: noop,
    requestAnimationFrame: () => 0, cancelAnimationFrame: noop,
    document,
    location: {
        search, hash: '', hostname: 'voice.google.com', host: 'voice.google.com', protocol: 'https:',
        origin: 'https://voice.google.com', pathname: '/u/0/messages',
        href: 'https://voice.google.com/u/0/messages' + search,
    },
    navigator: { userAgent: 'harness' },
    URLSearchParams, URL,
    HTMLInputElement: function HTMLInputElement() {},
    HTMLTextAreaElement: function HTMLTextAreaElement() {},
    Event: function Event(type) { this.type = type; },
    InputEvent: function InputEvent(type) { this.type = type; },
    KeyboardEvent: function KeyboardEvent(type) { this.type = type; },
    MutationObserver: function MutationObserver() { this.observe = noop; this.disconnect = noop; },
    getComputedStyle: () => ({ display: 'block', visibility: 'visible', opacity: '1', getPropertyValue: () => '' }),
    matchMedia: () => ({ matches: false, addListener: noop, addEventListener: noop }),
    addEventListener: noop, removeEventListener: noop,
    innerWidth: 400, innerHeight: 800,
};
sandbox.window = sandbox;
sandbox.self = sandbox;
document.defaultView = sandbox;
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(injectPath, 'utf8'), sandbox, { filename: 'inject.js' });
const out = vm.runInContext('JSON.stringify(' + testCase.expr + ')', sandbox, { filename: 'expr' });
process.stdout.write(out === undefined ? 'undefined' : out);
