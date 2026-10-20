"""2.4 Testrunde W4: Anmeldenamen anderer Konten nie zeigen - Personensuche liefert pseudonyme Handles ('~…'), Freigaben
zeigen "Person" statt des Anmeldenamens, Chat-Mitglieder werden ueber members[].ref mit Suchtreffern abgeglichen."""
from hlidskjalf import sharing as S

H = "~abcdefghijklmnop"


def test_hits_never_show_username():
    rows = S.hit_rows({"users": [{"username": H, "name": "Anna Berg"}, {"username": "bert", "name": "bert"}]}, {"shares": []}, "view")
    assert [r["target"] for r in rows] == [H, "bert"]                 # Kennung bleibt undurchsichtig (PUT nimmt Handle)
    assert all(r["sub"] == "Person on this server" for r in rows)
    assert rows[0]["name"] == "Anna Berg"


def test_share_and_inherited_rows_show_person():
    st = {"shares": [{"type": "user", "target": H, "name": "Anna Berg", "role": "edit"},
                     {"type": "remote", "target": "@cara@b.example", "name": "Cara · b.example", "role": "view"}],
          "inherited": [{"type": "user", "target": H, "name": "Anna Berg", "role": "view", "note_id": 3, "title": "Oben"}]}
    rows = S.share_rows(st)
    assert rows[0]["sub"] == "Person" and H not in rows[0]["sub"]
    assert rows[0]["target"] == H                                    # Entfernen/Rolle aendern ueber den Handle
    assert rows[1]["sub"] != "Person"
    assert S.inherited_rows(st)[0]["sub"] == "Person"


def test_member_matches_search_hit_by_ref():
    mem = [{"username": "anna", "ref": "~aaaaaaaaaaaaaaaa", "name": "Anna"}, {"username": "bert", "name": "Bert"}]
    assert S.is_member("~aaaaaaaaaaaaaaaa", mem)
    assert S.is_member("bert", mem)                                   # aeltere Server: Benutzername
    assert not S.is_member("~bbbbbbbbbbbbbbbb", mem)
    assert not S.is_member("", mem) and not S.is_member(H, None)
