"""KI-Darstellung (Bugs-DB 2248) - reine Logik ohne GTK: feste Markdown-Teilmenge wie Server saga/ai_md.py und Web
static/ai.js. Zeilen (p, h, ul, ol) mit Stuecken (t Text, b fett, c Code, n Quelle). Vektoren: tests/vectors25/ki_md.json
(Kopie von docs/ki-markdown-vektoren.json aus dem Repo saga). Pango-Markup baut assistant.answer_markup.
"""
import re

_LI = re.compile(r"^\s*(?:[-*•]|([0-9]{1,2})[.)])\s+(.*)$")
_H = re.compile(r"^\s*#{1,6}\s+(.*)$")
_INL = re.compile(r"(\*\*[^*\n]+\*\*|__[^_\n]+__|`[^`\n]+`|\[[0-9]{1,2}(?:\s*[,;]\s*[0-9]{1,2})*\])")


def spans(s, cites=()):
    """Eine Zeile -> [[art, text]]; [n] nur als Quelle ("n"), wenn n in cites - sonst Text "[n]"."""
    out, pos = [], 0
    for m in _INL.finditer(s):
        if m.start() > pos:
            out.append(["t", s[pos:m.start()]])
        x = m.group(0)
        if x[0] == "[":
            for k, n in enumerate(re.split(r"\s*[,;]\s*", x[1:-1])):
                if k:
                    out.append(["t", " "])
                out.append(["n", int(n)] if int(n) in cites else ["t", f"[{n}]"])
        elif x[0] == "`":
            out.append(["c", x[1:-1]])
        else:
            out.append(["b", x[2:-2]])
        pos = m.end()
    if pos < len(s):
        out.append(["t", s[pos:]])
    merged = []
    for sp in out:
        if merged and sp[0] == "t" and merged[-1][0] == "t":
            merged[-1][1] += sp[1]
        else:
            merged.append(sp)
    return merged


def lines(text, cites=()):
    """Antwort -> [{"k": "p"|"h"|"ul"|"ol", "s": spans}]; leere Zeilen fallen weg."""
    out = []
    for ln in str(text or "").split("\n"):
        m = _LI.match(ln)
        if m:
            out.append({"k": "ol", "n": int(m.group(1)), "s": spans(m.group(2), cites)} if m.group(1)
                       else {"k": "ul", "s": spans(m.group(2), cites)})
            continue
        if not ln.strip():
            continue
        h = _H.match(ln)
        out.append({"k": "h" if h else "p", "s": spans(h.group(1) if h else ln, cites)})
    return out
