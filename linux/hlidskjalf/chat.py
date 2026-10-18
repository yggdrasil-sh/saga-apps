"""Chats mit Ende-zu-Ende-Verschluesselung (Server 1.5) - Zustand und Ablaeufe, ohne Oberflaeche (chatview.py).

Diese App = ein Chat-Geraet. Private Schluessel (Geraet + Identitaet + Backup) liegen NUR im Schluesselbund
(Secret Service, libsecret) - nie im Klartext auf der Platte, nie beim Server. Ohne Schluesselbund (z. B. Tests) nur im
Speicher, solange die App laeuft. Kryptografie ausschliesslich in chatcrypto.py (PyNaCl/libsodium).

Vertrauen (TOFU): der Identitaetsschluessel jedes Gegenuebers wird beim ersten Mal gemerkt (nicht geheim, Datei im
Datenordner); aendert er sich, warnt der Chat ("Sicherheitsnummer geaendert") und Senden wartet auf "Verstanden"."""
import json
import os
import re
import threading
import time
import urllib.parse

from gi.repository import GLib, GObject

from . import APP_ID, aichat, chatmedia, config
from . import chatcrypto as C
from .api import APIError, client
from .i18n import _
from .util import Debounce, bg

RETENTIONS = [0, 3600, 86400, 604800, 2592000, 7776000, 31536000]
NOTE_RX = re.compile(r"\[\[note:(\d+)\]\]")


def retention_label(s):
    return {0: _("Off"), 3600: _("1 hour"), 86400: _("1 day"), 604800: _("7 days"), 2592000: _("30 days"),
            7776000: _("90 days"), 31536000: _("1 year")}.get(s, str(s))


# Chat-Personensuche ohne Treffer: name@server, @name@server oder E-Mail -> Hinweis statt "Keine Treffer" (Chats ueber
# Servergrenzen gibt es noch nicht, Seiten aber per name@server teilbar; wie im Web: static/chat.js fedAddr/fedHint)
FED_RX = re.compile(r"@?[^@\s]+@[^@\s]+[.:][^@\s]+")
FED_GUIDE = {
    "de": "https://github.com/yggdrasil-sh/saga/blob/main/docs/ANLEITUNG.md#mit-anderen-saga-instanzen-teilen-f%C3%B6deration",
    "es": "https://github.com/yggdrasil-sh/saga/blob/main/docs/es/GUIA.md#compartir-con-otras-instancias-de-saga-federaci%C3%B3n",
    "en": "https://github.com/yggdrasil-sh/saga/blob/main/docs/en/GUIDE.md#sharing-with-other-saga-instances-federation",
}


def fed_address(q):
    """name@host, @name@host, vorname.name@firma.de (Server-Teil mit Punkt oder Doppelpunkt/Port)"""
    return bool(FED_RX.fullmatch((q or "").strip()))


def fed_hint_show(q, hits):
    """Hinweis nur ohne lokale Treffer (die haben Vorrang) und ab 3 Zeichen"""
    return hits == 0 and len((q or "").strip()) >= 3 and fed_address(q)


def fed_guide_url(lang):
    """Anleitung, Abschnitt Foederation: de/es eigene Anleitung, sonst englisch"""
    return FED_GUIDE.get((lang or "")[:2].lower(), FED_GUIDE["en"])


def fed_hint_texts():
    return (_("Chats with people on other Saga servers aren't available yet. You can share pages with name@server (using the person's public name).")
            + " " + _("Find people on this server by name or username, not by email address."), _("Guide: federation"))


def fmt_recovery(rk):
    n = C.norm_recovery(rk) or ""
    return "-".join(n[i:i + 4] for i in range(0, len(n), 4))


def recovery_lines(rk):
    """Anzeige: zwei Zeilen zu je 4 Gruppen - nie mitten in einer Gruppe umbrechen"""
    g = fmt_recovery(rk).split("-")
    return "-".join(g[:4]) + ("\n" + "-".join(g[4:]) if len(g) > 4 else "")


class ChatError(Exception):
    """Fehler mit fertigem Text fuer die Oberflaeche"""

    def __init__(self, msg, code=None, data=None):
        super().__init__(msg)
        self.code, self.data = code, data


class SafetyChanged(ChatError):
    def __init__(self, uid, name):
        super().__init__(_("{name}'s safety number has changed – check it first or choose “Got it”.").format(name=name))
        self.uid, self.name = uid, name


# --- Schluessel im Schluesselbund ---------------------------------------------------------------------------------
_SCHEMA = None
_mem = {}                       # ohne Schluesselbund: nur im Speicher
_klock = threading.Lock()


_kr = {"ok": None}              # Secret Service erreichbar? None = noch nicht geprueft, False = Schluessel nur im Speicher


def _secret():
    """libsecret, wenn der Schluesselbund-Dienst wirklich erreichbar ist (nicht nur die Typelib da ist), sonst None
    (Schluessel nur im Speicher, wie ohne Schluesselbund). Die erste Pruefung ist ein D-Bus-Aufruf - nur im Hintergrund."""
    global _SCHEMA
    if not config.Secret or _kr["ok"] is False:
        return None
    S = config.Secret
    if _SCHEMA is None:
        _SCHEMA = S.Schema.new(APP_ID + ".Chat", S.SchemaFlags.NONE,
                               {"server": S.SchemaAttributeType.STRING, "uid": S.SchemaAttributeType.STRING})
    if _kr["ok"] is None:
        try:
            S.password_lookup_sync(_SCHEMA, {"server": "-", "uid": "-"}, None)
            _kr["ok"] = True
        except GLib.Error:
            _kr["ok"] = False                             # kein Dienst (Fenstermanager ohne Schluesselbund ...)
            return None
    return S


def keys_persistent():
    """Bleiben die Chat-Schluessel ueber einen Neustart? Nein ohne Typelib und wenn der Dienst nicht erreichbar war
    bzw. das Speichern scheiterte (BUG-028) - dann zeigt die Oberflaeche den Schluesselbund-Hinweis."""
    return bool(config.Secret) and _kr["ok"] is not False


def _kattrs(server, uid):
    return {"server": config.origin(server) or server, "uid": uid}


def load_keys(server, uid):
    """-> {device:{id, enc_pk, enc_sk, sign_pk, sign_sk}, ident_pk, ident_sk, backup_pk, backup_sk} (Bytes) oder None"""
    raw = None
    S = _secret()
    if S is not None:
        try:
            raw = S.password_lookup_sync(_SCHEMA, _kattrs(server, uid), None)
        except GLib.Error:
            raw = None
    if not raw:
        with _klock:
            raw = _mem.get((config.origin(server), uid))
    if not raw:
        return None
    try:
        d = json.loads(raw)
        dv = d["device"]
        out = {"device": {"id": dv["id"], "enc_pk": C.unb64(dv["enc_pk"], 32), "enc_sk": C.unb64(dv["enc_sk"], 32),
                          "sign_pk": C.unb64(dv["sign_pk"], 32), "sign_sk": C.unb64(dv["sign_sk"], 64)}}
        out.update(C.parse_plain(json.dumps({"ident_sk": d["ident_sk"], "backup_sk": d["backup_sk"]})))
        return out
    except (ValueError, KeyError, TypeError, C.CryptoError):
        return None


def save_keys(server, uid, k):
    dv = k["device"]
    raw = json.dumps({"v": 1, "device": {"id": dv["id"], "enc_pk": C.b64(dv["enc_pk"]), "enc_sk": C.b64(dv["enc_sk"]),
                                         "sign_pk": C.b64(dv["sign_pk"]), "sign_sk": C.b64(dv["sign_sk"])},
                      "ident_sk": C.b64(k["ident_sk"]), "backup_sk": C.b64(k["backup_sk"])})
    S = _secret()
    if S is not None:
        try:
            if S.password_store_sync(_SCHEMA, _kattrs(server, uid), S.COLLECTION_DEFAULT, _("Saga – chat keys of this device"), raw, None):
                return True
        except GLib.Error:
            pass
        # Nicht abbrechen: der Server kennt Identitaet bzw. Geraet schon - ohne Schluessel waeren sie verloren (BUG-028).
        # Wie ohne Schluesselbund: im Speicher behalten, die Oberflaeche zeigt den Hinweis (keys_persistent()).
        _kr["ok"] = False
    with _klock:
        _mem[(config.origin(server), uid)] = raw
    return False


def delete_keys(server, uid):
    S = _secret()
    if S is not None:
        try:
            S.password_clear_sync(_SCHEMA, _kattrs(server, uid), None)
        except GLib.Error:
            pass
    with _klock:
        _mem.pop((config.origin(server), uid), None)


# --- Vertrauen (TOFU) ---------------------------------------------------------------------------------------------
TRUST_FILE = os.path.join(config.DATA_DIR, "chat-trust.json")
_tlock = threading.Lock()


def _trust_all():
    try:
        with open(TRUST_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def _tkey(me):
    return f"{config.origin(config.settings.server)}|{me}"


def trust_get(me, uid):
    with _tlock:
        return dict((_trust_all().get(_tkey(me)) or {}).get(uid) or {})


def trust_set(me, uid, **kw):
    with _tlock:
        d = _trust_all()
        mine = d.setdefault(_tkey(me), {})
        e = mine.setdefault(uid, {})
        e.update(kw)
        try:
            config.atomic_write(TRUST_FILE, json.dumps(d))
        except OSError:
            pass
        return dict(e)


def trust_check(me, uid, ident_pub):
    """Identitaetsschluessel eines Gegenuebers pruefen/merken -> 'new' | 'same' | 'changed' (noch nicht bestaetigt)"""
    if not ident_pub or uid == me:
        return "same"
    t = trust_get(me, uid)
    if not t.get("ident_pub"):
        trust_set(me, uid, ident_pub=ident_pub, verified=False)
        return "new"
    if t["ident_pub"] == ident_pub:
        return "same"
    if t.get("pending") != ident_pub:
        trust_set(me, uid, pending=ident_pub)
    return "changed"


def trust_accept(me, uid):
    """"Verstanden": neuen Schluessel uebernehmen (nicht mehr ueberprueft)"""
    t = trust_get(me, uid)
    if t.get("pending"):
        trust_set(me, uid, ident_pub=t["pending"], pending=None, verified=False)


def trust_verified(me, uid, ident_pub):
    trust_set(me, uid, ident_pub=ident_pub, pending=None, verified=True)


# --- Zustand -----------------------------------------------------------------------------------------------------
class ChatState(GObject.Object):
    """status: 'unknown' | 'off' (Server ohne Chats/abgeschaltet) | 'nocrypto' (PyNaCl fehlt) | 'setup' (noch nicht
    eingerichtet) | 'device' (eingerichtet, aber dieses Geraet hat keine Schluessel) | 'ready'"""
    __gsignals__ = {
        "changed": (GObject.SignalFlags.RUN_FIRST, None, ()),              # Status, Liste, Ungelesen
        "messages": (GObject.SignalFlags.RUN_FIRST, None, (int,)),          # Nachrichten eines Chats geaendert
        "chat-changed": (GObject.SignalFlags.RUN_FIRST, None, (int,)),      # Details eines Chats (Mitglieder, Name ...)
        "typing": (GObject.SignalFlags.RUN_FIRST, None, (int,)),
        "link-request": (GObject.SignalFlags.RUN_FIRST, None, (object,)),   # anderes Geraet moechte Schluessel
        "link-done": (GObject.SignalFlags.RUN_FIRST, None, (str,)),         # Kopplung (dieses neue Geraet): approved|expired|gone
        "ai-pending": (GObject.SignalFlags.RUN_FIRST, None, (int,)),        # KI-Antwort in einem Chat laeuft/geaendert/fertig
        # Chat geloescht/geleert (2.1): (chat, wie, Name) - wie: "removed" (fuer alle weg) | "hidden" (Direkt-Chat fuer mich
        # geloescht) | "cleared" (Verlauf geleert, Chat bleibt); Name: wer es getan hat ("" = ich bzw. unbekannt)
        "chat-gone": (GObject.SignalFlags.RUN_FIRST, None, (int, str, str)),
    }

    def __init__(self):
        super().__init__()
        self.clear()
        self._list_soon = Debounce(400, self.load_chats)

    def clear(self):
        self.status = "unknown"
        self.me = None
        self.keys = None
        self.chats = []
        self._chats_at = 0.0       # letzte geladene Chat-Liste (monotonic)
        self.unread = 0
        self.cache = {}            # chat-id -> {"msgs": [...], "more", "last_seq", "loaded", "rc": recipients}
        self.typing = {}           # chat-id -> {uid: (name, bis)}
        self.notes = {}            # Seiten-Titel fuer [[note:ID]]: id -> {title, icon} | None (kein Zugriff)
        self.open_chat = None      # gerade angezeigter Chat (Gelesen-Markierung, keine Zaehler-Erhoehung)
        self.limits = {}
        self.link_wait = None      # neues Geraet: {"id", "device", "code", "expires"}
        self.server = config.settings.server
        self._seen_links = set()
        for p in list((getattr(self, "ai_pending", None) or {}).values()):
            if p.get("stream"):
                p["stream"].cancel()
        self.ai_pending = {}       # chat-id -> {"text", "stream", "q_msg"}: laufende Frage an die KI (2.1)

    # --- Grundlagen ---------------------------------------------------------------------------------------------
    @property
    def uid(self):
        return (self.me or {}).get("uid") or config.account()

    @property
    def ready(self):
        return self.status == "ready" and self.keys is not None

    @property
    def device_id(self):
        return self.keys["device"]["id"] if self.keys else ""

    AVAILABLE_STATES = ("setup", "device", "ready", "nocrypto")

    def _remember_available(self, srv):
        """Merken, ob dieser Server Chats hat - offline (Status noch unbekannt) bleibt "Chats" dann in der Seitenleiste
        (BUG-113)"""
        o = config.origin(srv) or srv
        seen = [x for x in config.settings.get("chat_servers") or [] if isinstance(x, str)]
        if self.status in self.AVAILABLE_STATES and o not in seen:
            config.settings.set("chat_servers", seen + [o])
        elif self.status == "off" and o in seen:
            config.settings.set("chat_servers", [x for x in seen if x != o])

    @property
    def shown(self):
        """"Chats" anzeigen: Status bekannt und Chats moeglich, oder noch unbekannt (offline) und frueher verfuegbar"""
        if self.status in self.AVAILABLE_STATES:
            return True
        srv = config.settings.server
        return self.status == "unknown" and bool(srv) and (config.origin(srv) or srv) in (config.settings.get("chat_servers") or [])

    def reset(self):
        """anderer Server / abgemeldet"""
        self.clear()
        chatmedia.clear()
        self.emit("changed")

    def refresh(self, done=None):
        """GET /api/chat/me -> Status (und lokale Schluessel pruefen)"""
        if not C.AVAILABLE:
            self.status = "nocrypto"
            self.emit("changed")
            return
        srv = config.settings.server

        def work():
            _secret()                                     # Schluesselbund-Dienst pruefen (D-Bus, nur im Hintergrund)
            uid = config.account(srv)
            keys = load_keys(srv, uid) if uid else None
            q = f"?device={keys['device']['id']}" if keys else ""
            me = client.get("api/chat/me" + q)
            uid2 = (me or {}).get("uid")
            if uid2 and uid2 != uid:
                keys = load_keys(srv, uid2)
            return me, keys

        def fin(r):
            me, keys = r
            if srv != config.settings.server:
                return
            self.server = srv
            self.me = me if isinstance(me, dict) else {}
            self.limits = self.me.get("limits") or {}
            ident = self.me.get("identity")
            mine = {d["id"]: d for d in self.me.get("devices") or [] if isinstance(d, dict) and d.get("id")}
            if not self.me.get("enabled", True):
                self.status = "off"
            elif not ident:
                self.status = "setup"
                self.keys = None
            elif keys and keys["ident_pk"] == C.unb64(ident.get("ident_pub") or "", None) \
                    and keys["device"]["id"] in mine and not mine[keys["device"]["id"]].get("revoked"):
                self.keys = keys
                self.status = "ready"
            else:
                if keys:                                  # entfernt oder neu begonnen: alte Schluessel weg
                    delete_keys(srv, self.uid)
                self.keys = None
                self.status = "device"
            self._remember_available(srv)
            self.emit("changed")
            if self.status == "ready":
                self.load_chats()
                if self.me.get("links"):
                    self.check_links()
            if done:
                done()

        def err(e):
            if isinstance(e, APIError) and e.code == 404:
                self.status = "off"
                self.emit("changed")
            if done:
                done()
        bg(work, done=fin, error=err)

    # --- Einrichtung ---------------------------------------------------------------------------------------------
    def setup(self, rk, password=None, reset=False, code=None):
        """Hintergrund: neue Identitaet + dieses Geraet anmelden (reset = neu beginnen; Bestaetigung mit Passwort oder -
        Server ab 2.1 - mit dem 6-stelligen Code der Authenticator-App). -> Schluessel"""
        uid = self.uid
        ident, dev = C.identity(), C.device()
        body = {"ident_pub": C.b64(ident["ident_pk"]), "backup_pub": C.b64(ident["backup_pk"]),
                "backup_sig": C.b64(C.sign(C.backup_msg(uid, ident["backup_pk"]), ident["ident_sk"])),
                "bundle": C.seal_bundle(uid, rk, ident),
                "device": C.device_api(uid, ident["ident_sk"], dev, config.device_name())}
        if reset:
            body["reset"] = True
            if password:
                body["password"] = password
            elif code:
                body["code"] = code
        try:
            client.json("PUT", "api/chat/identity", body)
        except APIError as e:
            raise ChatError(str(e), e.code, e.data) from e
        k = dict(ident, device=dev)
        save_keys(config.settings.server, uid, k)
        return k

    def adopt_keys(self, k):
        """Haupt-Thread: Schluessel uebernommen -> bereit"""
        self.keys = k
        self.status = "ready"
        old, self.cache = list(self.cache), {}
        self.emit("changed")
        for cid in old:                                   # offene Unterhaltungen neu zeichnen: keine alten, mit den
            self.emit("messages", cid)                    # vorigen Schluesseln entschluesselten Nachrichten (BUG-116)
        if self.open_chat in old:
            self.load_messages(self.open_chat)            # mit den neuen Schluesseln (aeltere: nicht entschluesselbar)
        self.refresh()

    def unlock(self, rk):
        """Hintergrund: Wiederherstellungsschluessel -> Buendel oeffnen, dieses Geraet selbst signiert anmelden"""
        uid = self.uid
        ident = (self.me or {}).get("identity") or {}
        if not C.norm_recovery(rk):
            raise ChatError(_("A recovery key has 32 characters (A–Z, 2–7)."))
        try:
            k = C.open_bundle(uid, rk, ident.get("bundle"))
        except C.CryptoError as e:
            if e.args and e.args[0] in ("kdf", "bundle"):           # Buendel ausserhalb der Grenzen bzw. kaputt
                raise ChatError(_("Unknown key format.")) from e
            raise ChatError(_("This key doesn't match.")) from e
        if C.b64(k["ident_pk"]) != ident.get("ident_pub"):
            raise ChatError(_("This key doesn't match."))
        dev = C.device()
        try:
            client.json("POST", "api/chat/devices", C.device_api(uid, k["ident_sk"], dev, config.device_name()))
        except APIError as e:
            raise ChatError(str(e), e.code) from e
        k["device"] = dev
        save_keys(config.settings.server, uid, k)
        return k

    def link_start(self):
        """Hintergrund: neues Geraet bittet ein vorhandenes um die Schluessel -> {id, code, expires}"""
        uid = self.uid
        dev = C.device()
        try:
            j = client.json("POST", "api/chat/link", {"id": dev["id"], "name": config.device_name(), "enc_pub": C.b64(dev["enc_pk"]),
                                                      "sign_pub": C.b64(dev["sign_pk"])})
        except APIError as e:
            raise ChatError(str(e), e.code) from e
        return {"id": j["id"], "device": dev, "code": C.link_code(uid, dev["id"], dev["enc_pk"], dev["sign_pk"]),
                "expires": j.get("expires")}

    def link_poll(self, lw):
        """Hintergrund: Stand der Kopplung -> ('pending'|'expired'|'gone', None) oder ('approved', Schluessel)"""
        j = client.get(f"api/chat/link/{urllib.parse.quote(lw['id'])}")
        st = (j or {}).get("state") or "gone"
        if st != "approved":
            return st, None
        dev = lw["device"]
        try:
            plain = C.unseal(j.get("sealed"), dev["enc_pk"], dev["enc_sk"]).decode()
            k = C.parse_plain(plain)
        except (C.CryptoError, UnicodeDecodeError) as e:
            raise ChatError(_("The transferred keys don't match your account.")) from e
        ident = (self.me or {}).get("identity") or {}
        if C.b64(k["ident_pk"]) != ident.get("ident_pub"):
            raise ChatError(_("The transferred keys don't match your account."))
        k["device"] = dev
        save_keys(config.settings.server, self.uid, k)
        return "approved", k

    def link_cancel(self, lw):
        if lw:
            bg(client.request, "DELETE", f"api/chat/link/{urllib.parse.quote(lw['id'])}", error=lambda e: None)

    # --- vorhandenes Geraet: Kopplungsanfragen ------------------------------------------------------------------
    def check_links(self):
        if not self.ready:
            return

        def fin(j):
            for ln in (j or {}).get("links") or []:
                if isinstance(ln, dict) and ln.get("id") not in self._seen_links:
                    self._seen_links.add(ln["id"])
                    self.emit("link-request", ln)
        bg(client.get, "api/chat/link", done=fin, error=lambda e: None)

    def link_code_for(self, ln):
        try:
            return C.link_code(self.uid, ln["device_id"], C.unb64(ln["enc_pub"], 32), C.unb64(ln["sign_pub"], 32))
        except (C.CryptoError, KeyError, TypeError):
            return None

    def link_approve(self, ln):
        """Hintergrund: Zertifikat fuer das neue Geraet + Buendel versiegelt an dessen Schluessel"""
        k = self.keys
        dev = {"id": ln["device_id"], "enc_pk": C.unb64(ln["enc_pub"], 32), "sign_pk": C.unb64(ln["sign_pub"], 32)}
        body = {"sig": C.device_cert(self.uid, k["ident_sk"], dev),
                "sealed": C.seal(C.bundle_plain(k["ident_sk"], k["backup_sk"]).encode(), dev["enc_pk"])}
        client.json("POST", f"api/chat/link/{urllib.parse.quote(ln['id'])}", body)

    LINK_AGAIN_S = 60

    def link_later(self, ln):
        """Kopplungsanfrage ohne Entscheidung geschlossen: nicht als gesehen merken, spaeter erneut fragen"""
        self._seen_links.discard(ln.get("id"))

        def again():
            if self.ready:
                self.check_links()
            return False
        GLib.timeout_add_seconds(self.LINK_AGAIN_S, again)

    def link_decline(self, ln):
        bg(client.request, "DELETE", f"api/chat/link/{urllib.parse.quote(ln['id'])}", error=lambda e: None)

    # --- Geraete und Wiederherstellungsschluessel ----------------------------------------------------------------
    def new_recovery(self, rk):
        """Hintergrund: neues Buendel mit neuem Schluessel (Schluessel und Nachrichten bleiben gleich)"""
        k = self.keys
        bundle = C.seal_bundle(self.uid, rk, k)
        client.json("PUT", "api/chat/bundle", {"bundle": bundle, "sig": C.bundle_update_sig(self.uid, bundle, k["ident_sk"])})

    def remove_device(self, did, done=None, error=None):
        def fin(_r):
            if did == self.device_id:
                self.forget_local()
            else:
                self.refresh()
            if done:
                done()
        bg(client.request, "DELETE", f"api/chat/devices/{urllib.parse.quote(did)}", done=fin, error=error)

    def forget_local(self):
        delete_keys(config.settings.server, self.uid)
        chatmedia.clear()
        self.keys = None
        self.cache = {}
        self.chats = []
        self.status = "device" if (self.me or {}).get("identity") else "setup"
        self.emit("changed")
        self.refresh()

    # --- Liste -------------------------------------------------------------------------------------------------------
    def load_chats(self, done=None):
        if not self.ready:
            if done:
                done()
            return

        def fin(j):
            j = j if isinstance(j, dict) else {}
            before = {c.get("id") for c in self.chats}
            self.chats = [c for c in j.get("chats") or [] if isinstance(c, dict)]
            for c in self.chats:
                tm = c.get("title_msg")
                if c.get("kind") == "free" and isinstance(tm, dict):
                    c["_title"] = self._title_of(c["id"], tm)
            self.unread = int(j.get("unread") or 0)
            self._chats_at = time.monotonic()
            self._sync_cleared(before)
            self.emit("changed")
            if done:
                done()

        def err(e):
            if done:
                done()
        bg(client.get, f"api/chats?device={self.device_id}", done=fin, error=err)

    def _sync_cleared(self, before):
        """Nach dem Laden der Liste (2.1): verpasste Loeschungen nachholen - Grenze cleared_seq ueber dem Zwischenspeicher
        -> bis dorthin leeren; frueher gelistete Chats, die fehlen (fuer alle geloescht, Direkt-Chat fuer mich geloescht,
        Gruppe verlassen) -> lokale Daten weg (nicht beim gerade offenen Chat: der meldet sich selbst per 404/Live)"""
        now = {c.get("id") for c in self.chats}
        for c in self.chats:
            cs = c.get("cleared_seq")
            cc = self.cache.get(c.get("id"))
            if isinstance(cs, int) and cs > 0 and cc is not None and int(cc.get("floor") or 0) < cs \
                    and any((m.get("seq") or 0) <= cs for m in cc.get("msgs") or []):
                self.purge(c["id"], upto=cs)
        for cid in before - now:
            if isinstance(cid, int) and cid in self.cache and cid != self.open_chat:
                self.purge(cid, drop=True)

    def revalidate(self, age=60):
        """Chat-Liste neu laden, wenn sie aelter als age Sekunden ist - Profilbilder (Adresse mit Version) und Namen der
        Gegenueber aendern sich ohne Live-Ereignis"""
        if self.ready and time.monotonic() - getattr(self, "_chats_at", 0) > age:
            self._chats_at = time.monotonic()             # nicht mehrfach gleichzeitig
            self.load_chats()

    def load_unread(self):
        """nur die Zahl fuer die Seitenleiste (auch ohne Schluessel auf diesem Geraet)"""
        def fin(j):
            if isinstance(j, dict) and "unread" in j:
                n = int(j.get("unread") or 0)
                if j.get("enabled") is False:
                    self.status = "off"
                if n != self.unread:
                    self.unread = n
                    self.emit("changed")
        bg(client.get, "api/chats/unread", done=fin, error=lambda e: None)

    def chat(self, cid):
        return next((c for c in self.chats if c.get("id") == cid), None)

    def _title_of(self, cid, tm):
        try:
            body, ok = self._open(cid, tm, tm.get("device"), tm.get("ident_pub"))
            return (body or {}).get("title") if isinstance(body, dict) and body.get("type") == "title" else None
        except C.CryptoError:
            return None

    def title(self, c):
        """Anzeigename: Direkt = Gegenueber, Gruppe = Gruppenname, Seite = Titel, freie Gruppe = entschluesselter Name
        bzw. die Namen der Mitglieder"""
        if not c:
            return ""
        if c.get("kind") == "free":
            t = c.get("_title")
            if t:
                return t
            names = [m.get("name") or m.get("username") for m in c.get("members") or [] if not m.get("me")]
            return ", ".join(names[:4]) + (" …" if len(names) > 4 else "") if names else _("Group")
        return c.get("name") or (_("Page") if c.get("kind") == "page" else _("Chat"))

    def kind_label(self, c):
        return {"dm": _("Direct chat"), "group": _("Group chat"), "free": _("Custom group"), "page": _("Page chat")}.get(
            (c or {}).get("kind"), _("Chat"))

    def peer(self, c):
        p = (c or {}).get("peer")
        return p if isinstance(p, dict) else None

    # --- Nachrichten --------------------------------------------------------------------------------------------------
    def _open(self, cid, m, dev, ident_pub):
        """-> (inhalt, echt?) ; CryptoError wenn nicht entschluesselbar"""
        k = self.keys
        if not k:
            raise C.CryptoError("no keys")
        if m.get("key_for") == "backup":
            body = C.decrypt(cid, m, k["backup_pk"], k["backup_sk"])
        else:
            body = C.decrypt(cid, m, k["device"]["enc_pk"], k["device"]["enc_sk"])
        sender = (m.get("sender") or {}).get("username") or ""
        ok = isinstance(dev, dict) and dev.get("uid", sender) == sender and bool(ident_pub) and \
            C.device_ok(sender, ident_pub, dict(dev, id=m.get("sender_device"))) and C.sig_ok(cid, m, dev.get("sign_pub"))
        return body, ok

    def _decode(self, cid, m, devices, idents):
        sender = m.get("sender") if isinstance(m.get("sender"), dict) else None
        uname = (sender or {}).get("username") or ""
        out = {"seq": m.get("seq"), "id": m.get("id"), "sender": sender, "mine": uname == self.uid and bool(uname),
               "ts": m.get("ts"), "created": m.get("created"), "expires_at": m.get("expires_at"), "reply_to": m.get("reply_to"),
               "sender_device": m.get("sender_device"), "body": None, "state": "ok", "text": "", "files": [], "notes": []}
        if m.get("kind") == "call":                       # Anruf-Eintrag (2.1): Klartext-Metadaten vom Server, nichts zu pruefen
            from .calls import parse_entry
            k = parse_entry(m)
            out["state"], out["call"] = ("call", k) if k else ("undecryptable", None)
            return out
        dev = (devices or {}).get(m.get("sender_device"))
        ident_pub = (idents or {}).get(uname)
        try:
            body, ok = self._open(cid, m, dev, ident_pub)
        except (C.CryptoError, KeyError, TypeError, ValueError):
            out["state"] = "undecryptable"
            return out
        if not isinstance(body, dict):
            out["state"] = "undecryptable"
            return out
        out["body"] = body
        out["state"] = "ok" if ok else "unverified"
        out["ai_state"] = aichat.check_reply(body, m.get("ai"))     # KI-Antwort (2.1): nur mit passendem Beleg "ok"
        if out["ai_state"]:
            out["ai_by"] = (m.get("ai") or {}).get("by") if isinstance(m.get("ai"), dict) else None
        out["text"] = str(body.get("text") or "")[:20000]
        out["files"] = [f for f in body.get("files") or [] if isinstance(f, dict) and f.get("id")][:10]
        out["notes"] = [int(x) for x in body.get("notes") or [] if isinstance(x, int) or (isinstance(x, str) and x.isdigit())]
        if uname and ident_pub and not out["mine"]:
            trust_check(self.uid, uname, ident_pub)
        return out

    def load_messages(self, cid, older=False, done=None):
        if not self.ready:
            return
        c = self.cache.setdefault(cid, {"msgs": [], "more": False, "last_seq": 0, "loaded": False})
        q = f"api/chats/{cid}/messages?device={self.device_id}&limit=50&calls=1"     # calls=1: Anruf-Eintraege (2.1)
        if older and c["msgs"]:
            q += f"&before={c['msgs'][0]['seq']}"
        elif c["loaded"] and c["msgs"]:
            q += f"&after={max(x['seq'] or 0 for x in c['msgs'])}"

        def work():
            j = client.get(q)
            return j, [self._decode(cid, m, j.get("devices"), j.get("idents")) for m in j.get("messages") or [] if isinstance(m, dict)]

        def fin(r):
            j, msgs = r
            floor = int(c.get("floor") or 0)                 # inzwischen geloescht (2.1): nichts unter der Grenze zurueckholen
            if floor:
                msgs = [m for m in msgs if (m.get("seq") or 0) > floor]
            have = {m["id"] for m in c["msgs"]}
            new = [m for m in msgs if m["id"] not in have]
            if older:
                c["msgs"] = new + c["msgs"]
                c["more"] = bool(j.get("more"))
            elif c["loaded"]:
                c["msgs"] = c["msgs"] + new
            else:
                c["msgs"] = msgs
                c["more"] = bool(j.get("more"))
            c["msgs"].sort(key=lambda m: m.get("seq") or 0)
            c["loaded"] = True
            c["last_seq"] = max(int(j.get("last_seq") or 0), c.get("last_seq") or 0)
            c["last_read"] = j.get("last_read")
            self._resolve_notes([n for m in new for n in m["notes"]] + [int(x) for m in new for x in NOTE_RX.findall(m["text"])])
            self.emit("messages", cid)
            if done:
                done()

        def err(e):
            c["error"] = str(e)
            self.emit("messages", cid)
            if done:
                done()
        bg(work, done=fin, error=err)

    def _resolve_notes(self, ids):
        want = sorted({i for i in ids if i not in self.notes and i > 0})[:100]
        if not want:
            return

        def fin(j):
            got = (j or {}).get("notes") or {}
            for i in want:
                self.notes[i] = got.get(str(i))
            for cid in list(self.cache):
                self.emit("messages", cid)
        bg(client.get, "api/chat/notes?ids=" + ",".join(map(str, want)), done=fin, error=lambda e: None)

    def mark_read(self, cid):
        c = self.cache.get(cid)
        ch = self.chat(cid)
        if not c or not c["msgs"]:
            return
        seq = max(m.get("seq") or 0 for m in c["msgs"])
        if ch is not None and ch.get("unread"):
            # stummgeschaltete und archivierte (2.1) Chats zaehlen nicht zur Gesamtzahl
            self.unread = max(0, self.unread - (0 if ch.get("muted") or ch.get("archived") else int(ch.get("unread") or 0)))
            ch["unread"] = 0
            self.emit("changed")
        if c.get("read_seq") == seq:
            return
        c["read_seq"] = seq

        def fin(j):
            if isinstance(j, dict) and "unread" in j:
                self.unread = int(j["unread"] or 0)
                self.emit("changed")
        bg(client.json, "POST", f"api/chats/{cid}/read", {"seq": seq}, done=fin, error=lambda e: None)

    _typing_sent = {}

    def send_typing(self, cid):
        now = time.monotonic()
        if now - self._typing_sent.get(cid, 0) < 2.5:
            return
        self._typing_sent[cid] = now
        bg(client.json, "POST", f"api/chats/{cid}/typing", {}, error=lambda e: None)

    def typing_names(self, cid):
        now = time.monotonic()
        t = self.typing.get(cid) or {}
        return [n for u, (n, until) in t.items() if until > now]

    # --- Empfaenger pruefen und senden --------------------------------------------------------------------------------
    def check_recipients(self, rc):
        """Empfaenger der API pruefen (Zertifikate, Backup-Signaturen, Vertrauen) -> ({empfaenger: pk}, nicht eingerichtet)"""
        me = self.uid
        out, missing = {}, []
        for r in rc or []:
            uname, name = r.get("username"), r.get("name") or r.get("username")
            if not r.get("ready"):
                missing.append(name)
                continue
            ip = r.get("ident_pub")
            if not C.backup_ok(uname, ip, r.get("backup_pub"), r.get("backup_sig")):
                raise ChatError(_("Invalid keys from {name} – sending cancelled.").format(name=name))
            if uname == me:
                if not self.keys or C.b64(self.keys["ident_pk"]) != ip:
                    raise ChatError(_("Your keys on the server don't match this device."))
            elif trust_check(me, uname, ip) == "changed":
                raise SafetyChanged(uname, name)
            out["backup:" + uname] = C.unb64(r["backup_pub"], 32)
            for d in r.get("devices") or []:
                if not C.device_ok(uname, ip, d):
                    raise ChatError(_("Invalid keys from {name} – sending cancelled.").format(name=name))
                out[d["id"]] = C.unb64(d["enc_pub"], 32)
        return out, missing

    def recipients(self, cid, fresh=False):
        """Hintergrund: Empfaenger (zwischengespeichert bis chat.changed)"""
        c = self.cache.setdefault(cid, {"msgs": [], "more": False, "last_seq": 0, "loaded": False})
        if fresh or not c.get("rc"):
            c["rc"] = (client.get(f"api/chats/{cid}/recipients") or {}).get("recipients") or []
        return c["rc"]

    def verify_recipients(self, cid):
        """Hintergrund, beim Oeffnen eines Chats: Empfaenger frisch holen und pruefen - eine geaenderte Sicherheitsnummer
        wird so schon vor dem ersten Senden gemerkt (pending), nicht erst beim Sendeversuch (BUG-076)"""
        try:
            self.check_recipients(self.recipients(cid, fresh=True))
        except ChatError:
            pass                                          # geaendert -> pending; anderes meldet das Senden
        return True

    def upload_file(self, cid, data, name, mime):
        """Hintergrund: Datei verschluesseln und hochladen -> Eintrag fuer den Inhalt"""
        blob, key = C.encrypt_file(cid, data)
        j = client.json("POST", f"api/chats/{cid}/files", raw=blob, ctype="application/octet-stream", timeout=180)
        chatmedia.put_blob(j["id"], blob, cid)             # eigene Anhaenge nicht gleich wieder herunterladen
        return {"id": j["id"], "k": C.b64(key), "n": name, "m": mime or "application/octet-stream", "s": len(data)}

    def upload_attachments(self, cid, files):
        """Hintergrund: Anhaenge [(name, mime, bytes)] hochladen; Bilder bekommen w/h und ein Vorschaubild ("th", 2.1-c),
        solange die Anhang-Grenze je Nachricht reicht. -> (Eintraege fuer den Inhalt, alle Datei-IDs)"""
        lim = self.limits or {}
        # Grenze fuer Datei-Kennungen je Nachricht (Anhaenge + Vorschaubilder): neuer Server "file_ids_per_msg" (20),
        # aeltere Server ohne das Feld zaehlen nur "files_per_msg" (10)
        nmax = int(lim.get("file_ids_per_msg") or lim.get("files_per_msg") or 10)
        out, ids = [], []
        for name, mime, data in files:
            e = self.upload_file(cid, data, name, mime)
            ids.append(e["id"])
            out.append(e)
        for e, (name, mime, data) in zip(out, files):
            if not chatmedia.is_image(e):
                continue
            t = chatmedia.make_thumb(data)
            if t is None:
                continue
            tb, (w, h), (tw, th) = t
            e["w"], e["h"] = w, h
            if len(ids) >= nmax:
                continue                                 # kein Platz mehr: Empfaenger erzeugen das Vorschaubild selbst
            te = self.upload_file(cid, tb, "thumb.jpg", "image/jpeg")
            e["th"] = {"id": te["id"], "k": te["k"], "m": "image/jpeg", "w": tw, "h": th, "s": len(tb)}
            ids.append(te["id"])
        return out, ids

    def send(self, cid, body, reply_to=None, kind="msg", file_ids=None, extra=None):
        """Hintergrund: verschluesseln, signieren, an alle aktuellen Empfaenger versiegeln, senden. 409 (Mitglieder/Geraete
        geaendert) -> einmal neu versiegeln. extra: weitere Felder des Sende-Bodys (nicht signiert, z. B. ai_reply).
        -> (Antwort mit "id" der Nachricht, nicht eingerichtete Namen)"""
        k = self.keys
        if not k:
            raise ChatError(_("Chats on this device"))
        rc = self.recipients(cid)
        rmap, missing = self.check_recipients(rc)
        enc = C.encrypt(cid, self.uid, k["device"], body, rmap, reply_to=reply_to)
        key = enc.pop("_key")
        req = {x: enc[x] for x in ("id", "ts", "sender_device", "body_ct", "sig", "keys")}
        if reply_to:
            req["reply_to"] = reply_to
        if kind != "msg":
            req["kind"] = kind
        if file_ids:
            req["files"] = list(file_ids)
        for k2, v2 in (extra or {}).items():
            req[k2] = v2
        for attempt in range(2):
            try:
                resp = client.json("POST", f"api/chats/{cid}/messages", req)
                resp = resp if isinstance(resp, dict) else {}
                resp.setdefault("id", req["id"])
                return resp, missing
            except APIError as e:
                if e.code == 409 and attempt == 0 and isinstance(e.data, dict) and isinstance(e.data.get("recipients"), list):
                    self.cache.setdefault(cid, {})["rc"] = e.data["recipients"]
                    rmap, missing = self.check_recipients(e.data["recipients"])
                    req["keys"] = C.reseal(key, rmap)
                    continue
                raise ChatError(str(e), e.code, e.data) from e
        raise ChatError(_("The members or their devices have changed – please send again."))

    def fetch_file(self, cid, f):
        """Hintergrund: Anhang laden und entschluesseln -> Bytes"""
        return chatmedia.original(cid, f)

    def delete_message(self, cid, mid, done=None, error=None):
        def fin(_r):
            c = self.cache.get(cid)
            if c:
                c["msgs"] = [m for m in c["msgs"] if m["id"] != mid]
            self.emit("messages", cid)
            if done:
                done()
        bg(client.request, "DELETE", f"api/chats/{cid}/messages/{mid}", done=fin, error=error)

    def patch(self, cid, fields, done=None, error=None):
        def fin(j):
            ch = (j or {}).get("chat")
            if isinstance(ch, dict):
                self._merge(ch)
            if done:
                done(ch)
        bg(client.json, "PATCH", f"api/chats/{cid}", fields, done=fin, error=error)

    def _merge(self, ch):
        old = self.chat(ch.get("id"))
        if old is not None and old is not ch:                # eben neu aufgenommen (dasselbe dict): nichts zu mischen,
            t = old.get("_title")                            # clear() wuerde es leeren (BUG-030)
            old.clear()
            old.update(ch)
            if t and "_title" not in ch:
                old["_title"] = t
        self.emit("chat-changed", ch.get("id"))
        self.emit("changed")

    def load_chat(self, cid, done=None, error=None):
        def fin(j):
            ch = (j or {}).get("chat")
            if isinstance(ch, dict):
                tm = ch.get("title_msg")
                if ch.get("kind") == "free" and isinstance(tm, dict):
                    ch["_title"] = self._title_of(cid, tm)
                if self.chat(cid) is None:
                    self.chats.insert(0, ch)
                self._merge(ch)
            if done:
                done(ch)
        bg(client.get, f"api/chats/{cid}?device={self.device_id}", done=fin, error=error)

    def new_chat(self, body, done, error):
        def fin(j):
            ch = (j or {}).get("chat") or {}
            if self.chat(ch.get("id")) is None and ch:
                self.chats.insert(0, ch)
            self.emit("changed")
            done(ch)
        bg(client.json, "POST", "api/chats", dict(body, device=self.device_id), done=fin, error=error)

    # --- Archivieren und Loeschen (2.1, docs/APP-API-CHAT.md) -----------------------------------------------------------
    @staticmethod
    def can_archive(c):
        """Server kann archivieren (Feld "archived" in der Chat-Liste) - aeltere Server: Funktion ausblenden"""
        return isinstance(c, dict) and "archived" in c

    @staticmethod
    def can_delete(c):
        """Server kann Chats loeschen (Feld "delete_for_all", auch wenn es null ist)"""
        return isinstance(c, dict) and "delete_for_all" in c

    def set_archived(self, cid, on, done=None, error=None):
        """PATCH /api/chats/{cid} {archived} (nur fuer mich); danach Liste und Ungelesen-Zahl (ohne Archiv) neu"""
        def fin(ch):
            self.load_chats()
            if done:
                done(ch)
        self.patch(cid, {"archived": bool(on)}, done=fin, error=error)

    def delete_chat(self, cid, scope="me", leave=False, done=None, error=None):
        """DELETE /api/chats/{cid}?scope=me|all; leave (freie Gruppe, nur mit "me"): danach austreten.
        Lokal: Zwischenspeicher bis zur Grenze leeren, Direkt-Chat bzw. geloeschten Chat aus der Liste. done(antwort)"""
        scope = "all" if scope == "all" else "me"
        c0 = self.chat(cid) or {}
        me = self.uid

        def work():
            r = client.json("DELETE", f"api/chats/{cid}?scope={scope}") or {}
            if leave and scope == "me" and not r.get("removed"):
                client.request("DELETE", f"api/chats/{cid}/members/{urllib.parse.quote(me, safe='')}")
                r = dict(r, left=True)
            return r

        def fin(r):
            r = r if isinstance(r, dict) else {}
            if r.get("removed") or r.get("left") or r.get("hidden") or (scope == "me" and c0.get("kind") == "dm"):
                self.purge(cid, drop=True)
                self.emit("chat-gone", cid, "removed" if r.get("removed") or r.get("left") else "hidden", "")
            else:
                upto = r.get("cleared_seq") if scope == "me" else None
                self.purge(cid, upto=upto if isinstance(upto, int) else None)
                c = self.chat(cid)
                if c is not None:
                    if isinstance(upto, int):
                        c["cleared_seq"] = max(int(c.get("cleared_seq") or 0), upto)
                    if r.get("archived"):
                        c["archived"] = True
                self.emit("chat-gone", cid, "cleared", "")
                self.emit("changed")
            self.load_chats()
            if done:
                done(r)
        bg(work, done=fin, error=error)

    @staticmethod
    def _file_ids(msgs):
        out = set()
        for m in msgs:
            for f in m.get("files") or []:
                if isinstance(f, dict):
                    out.add(f.get("id"))
                    th = chatmedia.thumb_ref(f)
                    if th:
                        out.add(th.get("id"))
        return {x for x in out if isinstance(x, str) and x}

    def purge(self, cid, upto=None, drop=False):
        """Lokale Daten eines Chats wirklich loeschen (2.1): entschluesselte Nachrichten bis upto (None = alles Bekannte),
        Anhaenge/Vorschaubilder im Speicher und auf der Platte, Tipp-Anzeige, laufende KI-Antwort, Benachrichtigungen
        (Glocke + Desktop-Mitteilung). drop: der Chat ist ganz weg (aus Liste und Zwischenspeicher)."""
        c = self.cache.get(cid) or {}
        msgs = c.get("msgs") or []
        if upto is None or drop:
            ch = self.chat(cid) or {}
            upto = max([m.get("seq") or 0 for m in msgs] + [int(ch.get("last_seq") or 0), int(c.get("last_seq") or 0),
                                                              int(upto or 0)])
        gone = [m for m in msgs if (m.get("seq") or 0) <= upto]
        kept = [] if drop else [m for m in msgs if (m.get("seq") or 0) > upto]
        chatmedia.purge_chat(cid, fids=self._file_ids(gone), keep=self._file_ids(kept))
        for m in gone:
            m.clear()                                     # Klartext nicht ueber andere Verweise weiter halten
        if drop:
            self.cache.pop(cid, None)
            self.chats = [x for x in self.chats if x.get("id") != cid]
        elif c:
            c["msgs"] = kept
            c["floor"] = max(int(c.get("floor") or 0), upto)
            c["more"] = False if not kept else c.get("more")
            c.pop("error", None)
        self.typing.pop(cid, None)
        p = self.ai_pending.pop(cid, None)
        if p and p.get("stream"):
            p["stream"].cancel()
        from .notifications import notifications
        notifications.drop_chat(cid)
        from gi.repository import Gio
        app = Gio.Application.get_default()
        if app is not None:
            app.withdraw_notification(f"chat-{cid}")
        if p:
            self.emit("ai-pending", cid)
        self.emit("messages", cid)
        if drop:
            self.emit("changed")

    def _cleared_live(self, d):
        """Live chat.cleared: scope "me" (anderes eigenes Geraet oder dieses) bzw. "all" (jemand hat den Verlauf fuer alle
        geloescht)"""
        cid = d.get("chat_id")
        if not isinstance(cid, int):
            return
        c = self.chat(cid)
        if d.get("scope") == "me":
            upto = d.get("cleared_seq") if isinstance(d.get("cleared_seq"), int) else None
            if (c or {}).get("kind") == "dm":
                self.purge(cid, upto=upto, drop=True)
                self.emit("chat-gone", cid, "hidden", "")
            else:
                self.purge(cid, upto=upto)
                if c is not None:
                    c["archived"] = True
                    if upto is not None:
                        c["cleared_seq"] = max(int(c.get("cleared_seq") or 0), upto)
                self.emit("chat-gone", cid, "cleared", "")
            self._list_soon()
            return
        by = d.get("by") if isinstance(d.get("by"), dict) else {}
        name = "" if by.get("username") == self.uid else str(by.get("name") or by.get("username") or "")
        self.purge(cid)
        self.emit("chat-gone", cid, "cleared", name)
        if self.ready:
            self.load_chat(cid, error=lambda e: self._gone(cid, e))   # neue Grenze cleared_seq
            if (self.cache.get(cid) or {}).get("loaded"):
                self.load_messages(cid)
        self._list_soon()

    def _removed_live(self, d):
        """Live chat.removed: Chat fuer alle geloescht - schliessen, aus Liste und Zwischenspeicher"""
        cid = d.get("chat_id")
        if not isinstance(cid, int):
            return
        by = d.get("by") if isinstance(d.get("by"), dict) else {}
        name = "" if by.get("username") == self.uid else str(by.get("name") or by.get("username") or "")
        self.purge(cid, drop=True)
        self.emit("chat-gone", cid, "removed", name)
        self._list_soon()

    # --- KI im Chat (2.1): nur auf Zuruf ------------------------------------------------------------------------------
    def ai_on(self, c):
        """Ist die KI in diesem Chat erlaubt (Chat-Schalter) und bietet der Server sie mir an?"""
        from .assistant import ai
        return bool((c or {}).get("ai")) and ai.chat_allowed

    def ask_ai(self, cid, text, qid, on_error=None):
        """Nach dem Senden einer Nachricht mit @Name: Frage + Verlauf an POST /api/chats/{cid}/ai, Antwort mitlaufend in
        ai_pending[cid] (Signal "ai-pending"), dann als verschluesselte Nachricht mit Beleg (ai_reply) an alle senden.
        Die App muss bis "done" offen bleiben - sonst gibt es keine Antwort im Chat. -> gestartet?"""
        from .assistant import Stream, ai_name
        if cid in self.ai_pending:
            return False
        name = ai_name()
        hist = aichat.history((self.cache.get(cid) or {}).get("msgs"), qid, name)
        body = {"q": aichat.question(text), "msg_id": qid, "history": hist}
        p = {"text": "", "q_msg": qid, "stream": None}
        self.ai_pending[cid] = p

        def end(err=None):
            if self.ai_pending.get(cid) is p:
                self.ai_pending.pop(cid, None)
            self.emit("ai-pending", cid)
            if err and on_error:
                on_error(err)

        def delta(t):
            p["text"] += t
            self.emit("ai-pending", cid)

        def reset():
            p["text"] = ""
            self.emit("ai-pending", cid)

        def done(d):
            text_ = str((d or {}).get("text") or "").strip()
            rep = (d or {}).get("reply") if isinstance((d or {}).get("reply"), dict) else {}
            if not text_ or not rep.get("id"):
                end(_("No reply received."))
                return
            p["text"], p["sending"] = text_, True
            self.emit("ai-pending", cid)
            nm = ((d or {}).get("persona") or {}).get("name") if isinstance((d or {}).get("persona"), dict) else None
            msg = aichat.reply_body(text_, rep["id"], nm or name)

            def fin(_r):
                if self.ready and (self.cache.get(cid) or {}).get("loaded"):
                    self.load_messages(cid, done=end)       # erst die gesendete Antwort zeigen, dann den Platzhalter weg
                else:
                    end()
                self._list_soon()
            bg(lambda: self.send(cid, msg, reply_to=qid, extra={"ai_reply": rep["id"]}), done=fin, error=lambda e: end(str(e)))

        def error(msg, _partial=False):
            end(msg)
        p["stream"] = Stream(f"api/chats/{cid}/ai", body, lambda _s: None, delta, done, error, reset)
        self.emit("ai-pending", cid)
        return True

    def set_ai(self, cid, allowed, done=None, error=None):
        """KI in diesem Chat erlauben/verbieten: PUT /api/chats/{cid}/ai {allowed}"""
        def fin(j):
            c = self.chat(cid)
            if c is not None:
                c["ai"] = bool((j or {}).get("ai", allowed))
                self.emit("chat-changed", cid)
            if done:
                done()
        bg(client.json, "PUT", f"api/chats/{cid}/ai", {"allowed": bool(allowed)}, done=fin, error=error)

    # --- Live ---------------------------------------------------------------------------------------------------------------
    def _avatar_changed(self, d):
        """Live "avatar.changed" {user, avatar} (Server 2.1): neue Bildadresse (bzw. None = Initialen) in Chat-Liste und
        geladenen Nachrichten eintragen, ohne Listen neu zu laden"""
        user, url = d.get("user"), d.get("avatar")
        if not isinstance(user, str) or not user or not (url is None or isinstance(url, str)):
            return

        def fix(p):
            if isinstance(p, dict) and p.get("username") == user and p.get("avatar") != url:
                p["avatar"] = url
                return True
            return False
        hit = False
        for c in self.chats:
            hit = fix(c.get("peer")) or hit
        if hit:
            self.emit("changed")
        for cid, cc in list(self.cache.items()):
            if any([fix(m.get("sender")) for m in cc.get("msgs") or []]):
                self.emit("messages", cid)

    def on_live(self, typ, d):
        if not isinstance(d, dict):
            return
        cid = d.get("chat_id")
        if typ == "chat.message":
            if cid in self.cache and self.cache[cid].get("loaded"):
                self.load_messages(cid)
            self._list_soon()
        elif typ == "chat.deleted":
            c = self.cache.get(cid)
            if c:
                c["msgs"] = [m for m in c["msgs"] if m["id"] != d.get("id")]
                self.emit("messages", cid)
            self._list_soon()
        elif typ == "chat.read":
            self._list_soon()
        elif typ == "chat.typing":
            u = d.get("user") if isinstance(d.get("user"), dict) else {}
            if u.get("username") and u.get("username") != self.uid:
                self.typing.setdefault(cid, {})[u["username"]] = (u.get("name") or u["username"], time.monotonic() + 6)
                self.emit("typing", cid)
                GLib.timeout_add(6200, lambda: (self.emit("typing", cid), False)[1])
        elif typ == "avatar.changed":
            self._avatar_changed(d)
        elif typ == "chat.cleared":                       # (2.1) Verlauf fuer mich (anderes Geraet) bzw. fuer alle geloescht
            self._cleared_live(d)
        elif typ == "chat.removed":                       # (2.1) Chat fuer alle geloescht
            self._removed_live(d)
        elif typ == "chat.changed" and "archived" in d:   # (2.1) archiviert/aus dem Archiv - auf einem meiner Geraete
            c = self.chat(cid)
            if c is not None and isinstance(d.get("archived"), bool) and c.get("archived") != d["archived"]:
                c["archived"] = d["archived"]
                self.emit("chat-changed", cid)
                self.emit("changed")
            if self.ready:
                self.load_chats()                         # Ungelesen-Zahl ohne Archiv; ausgeblendete Direkt-Chats bleiben weg
        elif typ == "chat.changed":
            c = self.cache.get(cid)
            if c:
                c.pop("rc", None)
            if self.ready:
                self.load_chat(cid, error=lambda e: self._gone(cid, e))
        elif typ == "chat.link":
            if d.get("approved") or d.get("gone"):
                lw = self.link_wait
                if lw and lw.get("id") == d.get("id"):
                    self.emit("link-done", "poll")
            elif self.ready:
                self.check_links()
        elif typ == "chat.device":
            if d.get("revoked") and d.get("id") == self.device_id:
                self.forget_local()
            else:
                self.refresh()
        elif typ == "reset":
            self.refresh()

    def _gone(self, cid, e):
        if isinstance(e, APIError) and e.code == 404:
            self.purge(cid, drop=True)                    # samt Anhaengen/Vorschaubildern (2.1)
            self.emit("chat-changed", cid)
            self.emit("changed")


chat = ChatState()


def safety_for(me_uid, me_pk, other_uid, other_pub_b64):
    try:
        return C.safety_number(me_uid, me_pk, other_uid, C.unb64(other_pub_b64, 32))
    except C.CryptoError:
        return None


def safety_qr_text(number):
    return "saga-safety:1:" + number.replace(" ", "")
