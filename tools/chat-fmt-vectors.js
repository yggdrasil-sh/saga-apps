// Testvektoren fuer die Android-App aus static/chat-fmt.js (echter Code, Mini-DOM): innerHTML ohne Klassen + strip().
// Aufruf: node tools/chat-fmt-vectors.js <saga>/static/chat-fmt.js > android/app/src/test/resources/vectors/chat_fmt.json
const fs = require('fs');
function Node(tag) { this.tag = tag; this.childNodes = []; this.nodeType = tag ? 1 : 3; this.className = ''; }
Node.prototype.appendChild = function (c) { this.childNodes.push(c); return c; };
Object.defineProperty(Node.prototype, 'lastChild', { get() { return this.childNodes[this.childNodes.length - 1] || null; } });
Object.defineProperty(Node.prototype, 'textContent', {
  get() { return this.nodeType === 3 ? this._t : this.childNodes.map(c => c.textContent).join(''); },
  set(v) { if (this.nodeType === 3) this._t = String(v); else this.childNodes = [txt(v)]; } });
function txt(t) { const n = new Node(null); n._t = String(t); return n; }
global.document = { createElement: t => new Node(t), createTextNode: txt };
global.window = {};
eval(fs.readFileSync(process.argv[2], 'utf8'));
const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const ser = n => n.nodeType === 3 ? esc(n._t) : '<' + n.tag + '>' + n.childNodes.map(ser).join('') + '</' + n.tag + '>';
const inputs = [
  'hallo', '', '*fett*', '_kursiv_', '~durch~', '`code`', '*fett* _kursiv_ ~weg~ `x<y`',
  'a_b_c 2*3*4', 'x*y* z', '*a*b', '(*in Klammern*)', '"*zitiert*"', '*ende*.', '*ende*!?', '* nicht*', '*nicht *', '**', '*a**b*',
  '*fett _kursiv_ drin*', '*_~tief~_*', '*1 _2 ~3 `4` 3~ 2_ 1*', '*a _b ~c *d _e_ d* c~ b_ a*',
  '`*nicht fett*`', '*vor `code` nach*', '``', '`a` `b`',
  '- eins\n- zwei', '• punkt\n- strich', '-kein Punkt', 'text\n- eins\n- zwei\nweiter',
  '> zitiert', '>ohne Leer', '> a\n> b\nc', 'vorher\n> zitiert\nnachher', '> *fett im Zitat*\n> - liste',
  '```\n<b>block</b>\n```', '```js\nlet a = 1;\n```', 'vor\n```\ncode\n```\nnach', '```inline```', '```\nnie zu', '```\na\n```\n```\nb\n```',
  'zeile1\nzeile2', 'a\n\nb', '\n', 'end\n',
  '<img src=x onerror="alert(1)"> *<script>alert(2)</script>* [x](javascript:alert(3)) a_b_c 2*3*4',
  '<b>fett?</b>', '&amp; &lt;', '*<a href="javascript:alert(1)">x</a>*', '`<script>`', '> <iframe src=//x>', '- <svg onload=alert(1)>',
  '```\n</code></pre><script>alert(1)</script>\n```', 'https://example.org/a_b_c_ und ~user/x~y', 'mail@x.org *ok*', '_a_b_', 'x *fett* y', '*a* b',
  '*mehr\nzeilig*', '~~doppelt~~', '_ _', '*x*', 'ä*ö*ü', '　*cjk*　', '<@~abcdefghijklmnop> *hi*',
];
const out = inputs.map(t => { const b = document.createElement('div'); window.SagaChatFmt.render(b, t); return { in: t, html: b.childNodes.map(ser).join(''), strip: window.SagaChatFmt.strip(t) }; });
process.stdout.write(JSON.stringify({ source: 'static/chat-fmt.js', vectors: out }, null, 1));
