"""Bloecke kopieren/ausschneiden/einfuegen (2.1-c), vor allem Bildbloecke.

Zwischenablage: "application/x-editor-js" - dasselbe Format wie der Web-Editor (Editor.js): JSON-Liste
[{"id", "tool", "data"}]; zusaetzlich "hk": {"server", "owner"} (Editor.js ignoriert das), damit beim Einfuegen klar ist,
ob die Bilddatei wiederverwendet werden kann (gleicher Server, gleicher Eigentuemer) oder neu hochgeladen werden muss.
Bildbloecke liegen ausserdem als Bild (Textur -> image/png ...) in der Zwischenablage - fuer andere Programme und den Chat;
Textbloecke als reiner Text."""
import copy
import json

from gi.repository import Gdk, Gio, GLib, GObject

from .util import new_block_id, plain

MIME = "application/x-editor-js"
TYPES = {"paragraph", "header", "list", "quote", "code", "delimiter", "image", "attaches", "embed", "table", "toggle", "columns",
         "callout", "buttons", "database", "tasks", "calendar", "immich", "warning", "checklist", "raw", "linkTool", "nextcloud",
         "widget"}


def block_text(b):
    """reiner Text eines Blocks (fuer andere Programme); Bild/Datei: leer"""
    d = b.get("data") or {}
    t = b.get("type")
    if t in ("paragraph", "header", "quote", "callout", "warning"):
        return plain(d.get("text") or "")
    if t == "code":
        return d.get("code") or ""
    if t in ("list", "checklist"):
        out = []

        def walk(items, depth):
            for it in items or []:
                if isinstance(it, dict):
                    out.append("  " * depth + "- " + plain(it.get("content") or it.get("text") or ""))
                    walk(it.get("items"), depth + 1)
                elif isinstance(it, str):
                    out.append("  " * depth + "- " + plain(it))
        walk(d.get("items"), 0)
        return "\n".join(out)
    if t == "toggle":
        return plain(d.get("text") or d.get("title") or "")
    return ""


def payload(blocks, server, owner):
    out = []
    for b in blocks:
        it = {"id": b.get("id"), "tool": b.get("type"), "data": b.get("data") or {}, "hk": {"server": server, "owner": owner}}
        if isinstance(b.get("tunes"), dict) and b["tunes"]:
            it["tunes"] = b["tunes"]                            # Block-Farbe u. a. (2.5) - wie Editor.js
        out.append(it)
    return json.dumps(out, ensure_ascii=False).encode()


def provider(blocks, server, owner, texture=None):
    """Inhalt fuer die Zwischenablage: Bloecke (+ Bild bzw. Text)"""
    parts = [Gdk.ContentProvider.new_for_bytes(MIME, GLib.Bytes.new(payload(blocks, server, owner)))]
    if texture is not None:
        parts.append(Gdk.ContentProvider.new_for_value(GObject.Value(Gdk.Texture, texture)))
    else:
        text = "\n\n".join(x for x in (block_text(b) for b in blocks) if x)
        if text:
            parts.append(Gdk.ContentProvider.new_for_bytes("text/plain;charset=utf-8", GLib.Bytes.new(text.encode())))
            parts.append(Gdk.ContentProvider.new_for_value(text))
    return Gdk.ContentProvider.new_union(parts)


def has_blocks(formats):
    return formats is not None and formats.contain_mime_type(MIME)


def _renew(b):
    b["id"] = new_block_id()
    d = b.get("data") or {}
    for x in d.get("blocks") or []:
        if isinstance(x, dict):
            _renew(x)
    for c in d.get("cols") or []:
        for x in (c.get("blocks") or []) if isinstance(c, dict) else []:
            if isinstance(x, dict):
                _renew(x)


def parse(data):
    """JSON aus der Zwischenablage -> [(Block mit neuen IDs, hk-Herkunft oder {})]; Unbekanntes wird uebergangen"""
    try:
        arr = json.loads(bytes(data).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return []
    if isinstance(arr, dict):
        arr = arr.get("blocks") or [arr]
    out = []
    for it in arr if isinstance(arr, list) else []:
        if not isinstance(it, dict):
            continue
        t = it.get("tool") or it.get("type")
        d = it.get("data")
        if t not in TYPES or not isinstance(d, dict):
            continue
        b = {"id": "", "type": t, "data": copy.deepcopy(d)}
        if isinstance(it.get("tunes"), dict) and it["tunes"]:
            b["tunes"] = copy.deepcopy(it["tunes"])
        _renew(b)
        hk = it.get("hk") if isinstance(it.get("hk"), dict) else {}
        out.append((b, hk))
    return out[:200]


def read(clipboard, done):
    """Bloecke aus der Zwischenablage lesen -> done([(Block, hk)]) im Hauptthread (leer bei Fehler)"""
    def got_stream(c, res):
        try:
            stream, _mime = c.read_finish(res)
        except GLib.Error:
            done([])
            return
        out = Gio.MemoryOutputStream.new_resizable()

        def spliced(o, r):
            try:
                o.splice_finish(r)
                data = o.steal_as_bytes().get_data()
            except GLib.Error:
                done([])
                return
            done(parse(data))
        out.splice_async(stream, Gio.OutputStreamSpliceFlags.CLOSE_SOURCE | Gio.OutputStreamSpliceFlags.CLOSE_TARGET,
                         GLib.PRIORITY_DEFAULT, None, spliced)
    clipboard.read_async([MIME], GLib.PRIORITY_DEFAULT, None, got_stream)


def reuse_ok(hk, server, owner):
    """Bilddatei wiederverwenden? Nur bei gleichem Server und gleichem Eigentuemer (sonst sieht der Eigentuemer der
    Zielseite die Datei evtl. nicht, und das Aufraeumen des Servers kennt die Verwendung nicht)"""
    if not hk:
        return owner == ""                                   # z. B. aus dem Web: nur in eigene Seiten unveraendert
    return hk.get("server") == server and (hk.get("owner") or "") == owner
