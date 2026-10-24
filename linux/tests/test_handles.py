"""2.4 Testrunde W4: Anmeldenamen anderer Konten nie zeigen - Personensuche liefert pseudonyme Handles ('~…'), Freigaben
zeigen "Person" statt des Anmeldenamens, Chat-Mitglieder werden ueber members[].ref mit Suchtreffern abgeglichen."""
from hlidskjalf import sharing as S

H = "~abcdefghijklmnop"


def test_hits_never_show_username():
    rows = S.hit_rows({"users": [{"username": H, "name": "Anna Berg"}, {"username": "bert", "name": "bert"}]}, {"shares": []}, "view")
    assert [r["target"] for r in rows] == [H, "bert"]                 # Kennung bleibt undurchsichtig (PUT nimmt Handle)
    # Testrunde 2 (N2): Merkmal fuer Gleichnamige = Handle (bzw. "sub" vom Server), nie der Anmeldename
    assert rows[0]["sub"] == "Person on this server · " + H and rows[1]["sub"] == "Person on this server"
    assert rows[0]["name"] == "Anna Berg"


def test_share_and_inherited_rows_show_person():
    st = {"shares": [{"type": "user", "target": H, "name": "Anna Berg", "role": "edit"},
                     {"type": "remote", "target": "@cara@b.example", "name": "Cara · b.example", "role": "view"}],
          "inherited": [{"type": "user", "target": H, "name": "Anna Berg", "role": "view", "note_id": 3, "title": "Oben"}]}
    rows = S.share_rows(st)
    assert rows[0]["sub"] == "Person · " + H                          # N2: Handle unterscheidet Gleichnamige
    assert rows[0]["target"] == H                                    # Entfernen/Rolle aendern ueber den Handle
    assert rows[1]["sub"] != "Person"
    assert S.inherited_rows(st)[0]["sub"] == "Person · " + H
    assert S.share_rows({"shares": [{"type": "user", "target": "anna", "name": "Anna", "role": "view"}]})[0]["sub"] == "Person"


def test_member_matches_search_hit_by_ref():
    mem = [{"username": "anna", "ref": "~aaaaaaaaaaaaaaaa", "name": "Anna"}, {"username": "bert", "name": "Bert"}]
    assert S.is_member("~aaaaaaaaaaaaaaaa", mem)
    assert S.is_member("bert", mem)                                   # aeltere Server: Benutzername
    assert not S.is_member("~bbbbbbbbbbbbbbbb", mem)
    assert not S.is_member("", mem) and not S.is_member(H, None)


def test_d01_sender_fallback_after_readable_names():
    """Bughunt D01: Ersetzt readable_names die Kennung des Absenders durch "Person on … (spqz)", gilt das weiter als
    'kein Name' - der Chat nimmt dann den Namen aus den Mitgliedern (vorher: Name begann mit '@~')"""
    from hlidskjalf import api
    h = "~s545sgsgkkyhspqz@b.example"
    snd = api.readable_names({"sender": {"name": "@" + h}})["sender"]
    assert snd["name"] != "@" + h and api.no_real_name(snd["name"], h)
    assert api.no_real_name("", h) and api.no_real_name("@" + h, h) and api.no_real_name("~s545sgsgkkyhspqz", h)
    assert not api.no_real_name("Anna", h)                                # echter Name bleibt
    local = api.readable_names({"name": "~s545sgsgkkyhspqz"})["name"]
    assert api.no_real_name(local, "~s545sgsgkkyhspqz")
    assert not api.no_real_name(local, "~aaaaaaaaaaaaaaaa")              # Ersatz einer anderen Person: nicht verwechseln
