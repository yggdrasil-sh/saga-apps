"""Nachgebauter Server fuer den Seitenbaum-Abgleich (GET api/notes?delta=1 / ?since=) - wie DeltaTests.swift (iOS):
Liste wie GET api/notes (Reihenfolge nach sort, bei Gleichstand id), jede Aenderung bekommt eine laufende Nummer;
since=c liefert die seit c geaenderten Seiten (mit "after" = Vorgaenger in der ganzen Liste) und die entfernten IDs."""
import json


class Rng:
    """reproduzierbarer Zufall (gleiche Formel wie in DeltaTests.swift)"""

    def __init__(self, s):
        self.s = s & 0xFFFFFFFFFFFFFFFF

    def next(self, n):
        self.s = (self.s * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        return (self.s >> 33) % max(n, 1)


class DeltaServer:
    def __init__(self):
        self.notes = {}           # id -> {"page", "sort", "changed"}
        self.trash = {}
        self.removed_at = {}
        self.seq, self.next_id, self.clock = 0, 1, 100.0

    def list(self):
        return [dict(n["page"]) for n in sorted(self.notes.values(), key=lambda n: (n["sort"], n["page"]["id"]))]

    def touch(self, pid, **fields):
        n = self.notes.get(pid)
        if n is None:
            return
        self.seq += 1
        if "sort" in fields:
            n["sort"] = fields.pop("sort")
        n["page"].update(fields)
        n["changed"] = self.seq

    def create(self, parent=None, kind="page", title=None):
        pid = self.next_id
        self.next_id += 1
        self.seq += 1
        self.clock += 1
        page = {"id": pid, "title": title or f"Seite {pid}", "icon": "", "parent_id": parent, "updated": self.clock,
                "tags": [], "pinned": False, "template": False, "kind": kind, "archived": False, "locked": False,
                "shared": False, "unread": False}
        self.notes[pid] = {"page": page, "sort": self.clock, "changed": self.seq}
        self.removed_at.pop(pid, None)
        return pid

    def subtree(self, pid):
        out, i = [pid], 0
        while i < len(out):
            p = out[i]
            out += sorted(n["page"]["id"] for n in self.notes.values() if n["page"]["parent_id"] == p)
            i += 1
        return out

    def trash_tree(self, pid):
        for x in self.subtree(pid):
            n = self.notes.pop(x, None)
            if n is not None:
                self.seq += 1
                self.trash[x] = n
                self.removed_at[x] = self.seq

    def restore(self, pid):
        n = self.trash.pop(pid, None)
        if n is None:
            return
        if n["page"]["parent_id"] is not None and n["page"]["parent_id"] not in self.notes:
            n["page"]["parent_id"] = None
        self.seq += 1
        n["changed"] = self.seq
        self.notes[pid] = n
        self.removed_at.pop(pid, None)

    def purge(self, pid):
        if self.trash.pop(pid, None) is not None:
            self.seq += 1
            self.removed_at[pid] = self.seq

    def response(self, since=None):
        """Antwort als Bytes wie der Server (since None = delta=1)"""
        lst = self.list()
        if since is None:
            return json.dumps({"cursor": f"c.{self.seq}", "full": True, "removed": [], "notes": lst}).encode()
        out = []
        for i, p in enumerate(lst):
            if self.notes[p["id"]]["changed"] > since:
                out.append(dict(p, after=None if i == 0 else lst[i - 1]["id"]))
        rem = sorted(k for k, v in self.removed_at.items() if v > since and k not in self.notes)
        return json.dumps({"cursor": f"c.{self.seq}", "full": False, "removed": rem, "notes": out}).encode()

    @staticmethod
    def seq_of(c):
        return int(c[2:]) if c else None

    def random_step(self, rng, step):
        """eine Zufallsaenderung wie in DeltaTests.swift"""
        ids = sorted(self.notes)
        pick = ids[rng.next(len(ids))] if ids else None
        k = rng.next(11)
        if k == 0:
            self.create(None if rng.next(3) == 0 else pick)
        elif k == 1 and pick:
            self.touch(pick, title=f"T{step}-{rng.next(99)}")
        elif k == 2 and pick:
            to = ids[rng.next(len(ids))]
            if to not in self.subtree(pick):
                self.touch(pick, parent_id=to)
        elif k == 3 and pick:
            self.touch(pick, sort=float(rng.next(300)))
        elif k == 4 and pick:
            self.trash_tree(pick)
        elif k == 5 and self.trash:
            self.restore(sorted(self.trash)[0])
        elif k == 6 and pick:
            self.touch(pick, archived=not self.notes[pick]["page"]["archived"])
        elif k == 7:
            db = self.create(pick, kind="database")
            for _ in range(1 + rng.next(3)):
                self.create(db)
        elif k == 8:
            dbs = [n["page"]["id"] for n in self.notes.values() if n["page"]["kind"] == "database"]
            rows = sorted(n["page"]["id"] for n in self.notes.values() if dbs and n["page"]["parent_id"] == dbs[0])
            if rows:
                self.touch(rows[0], title=f"Eintrag {rng.next(50)}", tags=["x"])
        elif k == 9 and self.trash:
            self.purge(sorted(self.trash)[-1])
        elif k == 10 and pick:
            self.touch(pick, shared=True, unread=not self.notes[pick]["page"]["unread"], pinned=True, icon="⭐")
