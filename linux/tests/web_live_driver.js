// Gegenprobe Linux <-> Web (Saga 2.5 E6): Browser (Tiptap, static/pm-live.js) als zweites Konto, gesteuert von
// tests/real25web.py ueber JSON-Zeilen auf stdin/stdout (ein Befehl je Zeile, Antwort {id, ok, v}).
// Befehle: open {nid}, state, txt {bid}, blocks, type {bid, pos: 'start'|'end', s}, ime {bid, s}, close.
// Umgebung: HK_TEST_BASE, CK (Cookie des Browser-Kontos), PLAYWRIGHT, PW_CHANNEL. Wie tests/browser_tiptap_live.js im
// Server-Repo (Vorlage), nur die Teile, die der Linux-Test braucht.
'use strict';
const PW = require(process.env.PLAYWRIGHT || 'playwright');
const readline = require('readline');
const BASE = process.env.HK_TEST_BASE, CK = process.env.CK;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const br = await PW.chromium.launch(process.env.PW_CHANNEL ? { channel: process.env.PW_CHANNEL } : {});
  const ctx = await br.newContext({ viewport: { width: 1200, height: 900 }, locale: 'de-DE' });
  await ctx.addCookies([{ name: 'saga', value: CK, domain: new URL(BASE).hostname, path: '/' }]);
  const p = await ctx.newPage();
  p.errs = []; p.patches = [];
  p.on('pageerror', (e) => p.errs.push(e.message));
  p.on('request', (r) => { if (r.method() === 'PATCH' && /\/api\/notes\/\d+$/.test(r.url())) p.patches.push(r.postData() || ''); });
  await p.goto(BASE + '/notes'); await sleep(400);
  await p.evaluate(() => HK.api('PUT', '/api/account/editor', { tiptap: true }));
  const st = () => p.evaluate(() => window.HKPmLive ? HKPmLive.state() : null);
  const txt = (id) => p.evaluate((id) => {
    const ed = HKPages.k.editor().editor(); let t = null;
    ed.state.doc.descendants((n) => { if (t === null && n.attrs && n.attrs.id === id) t = n.textContent; return t === null; });
    return t;
  }, id);
  const out = (o) => process.stdout.write(JSON.stringify(o) + '\n');
  out({ id: 0, ok: true, v: 'ready' });
  const rl = readline.createInterface({ input: process.stdin });
  for await (const line of rl) {
    let c; try { c = JSON.parse(line); } catch (e) { continue; }
    try {
      let v = null;
      if (c.cmd === 'open') {
        await p.goto(BASE + '/notes#' + c.nid); await p.reload();
        for (let i = 0; i < 200; i++) {
          const s = await p.evaluate((id) => window.HKPages && HKPages.k.cur() && HKPages.k.cur().id === id && document.querySelector('#pg-editor .pm-doc') && window.HKPmLive ? HKPmLive.state() : null, c.nid);
          if (s && s.mode === 'live') break;
          await sleep(50);
        }
        await sleep(300);
        p.patches = [];
        v = await st();
      } else if (c.cmd === 'state') {
        v = await st();
      } else if (c.cmd === 'txt') {
        v = await txt(c.bid);
      } else if (c.cmd === 'blocks') {
        v = await p.evaluate(() => HKPages.k.doc.blocks().filter((x) => x.parent == null).map((x) => x.id));
      } else if (c.cmd === 'type') {
        await p.evaluate(([id, pos]) => HKPages.k.doc.focus(id, pos), [c.bid, c.pos || 'end']);
        await p.keyboard.type(c.s, { delay: 25 });
        v = await txt(c.bid);
      } else if (c.cmd === 'ime') {
        await p.evaluate(([id]) => HKPages.k.doc.focus(id, 'end'), [c.bid]);
        const cdp = await ctx.newCDPSession(p);
        for (let i = 1; i <= c.s.length; i++) await cdp.send('Input.imeSetComposition', { text: c.s.slice(0, i), selectionStart: i, selectionEnd: i });
        await sleep(200);
        await cdp.send('Input.insertText', { text: c.s });
        v = await txt(c.bid);
      } else if (c.cmd === 'patches') {
        v = p.patches.filter((x) => /"body"/.test(x)).length;
      } else if (c.cmd === 'errs') {
        v = p.errs;
      } else if (c.cmd === 'close') {
        out({ id: c.id, ok: true, v: null });
        break;
      }
      out({ id: c.id, ok: true, v });
    } catch (e) {
      out({ id: c.id, ok: false, v: String(e && e.message || e) });
    }
  }
  await br.close();
  process.exit(0);
})().catch((e) => { process.stdout.write(JSON.stringify({ id: -1, ok: false, v: String(e) }) + '\n'); process.exit(1); });
