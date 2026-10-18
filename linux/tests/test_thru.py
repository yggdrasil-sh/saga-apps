"""Thai und Russisch: Datumswoerter im Editor ("@завтра в 9:00", "@พรุ่งนี้"), Kataloge (Pluralformen, vollstaendig)."""
import datetime as dt
import os
import re

from hlidskjalf.richtext import parse_date

PO = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "po")


def _in(n):
    return (dt.date.today() + dt.timedelta(days=n)).isoformat()


def test_datumswoerter_ru_th():
    assert parse_date("сегодня") == (_in(0), None)
    assert parse_date("Завтра") == (_in(1), None)
    assert parse_date("завтра в 9:00") == (_in(1), "09:00")
    assert parse_date("послезавтра") == (_in(2), None)
    assert parse_date("вчера") == (_in(-1), None)
    assert parse_date("วันนี้") == (_in(0), None)
    assert parse_date("พรุ่งนี้ 9:30") == (_in(1), "09:30")
    assert parse_date("มะรืนนี้") == (_in(2), None)
    assert parse_date("เมื่อวาน") == (_in(-1), None)


def test_wochentage_ru_th():
    today = dt.date.today()
    for q, wd in (("в пятницу", 4), ("понедельник", 0), ("в следующую среду", 2), ("воскресенье", 6),
                  ("วันศุกร์", 4), ("วันจันทร์หน้า", 0), ("พุธ", 2)):
        d = dt.date.fromisoformat(parse_date(q)[0])
        assert d.weekday() == wd, q
        assert 1 <= (d - today).days <= 7, q
    assert parse_date("в пятницу в 18:00")[1] == "18:00"
    assert parse_date("неизвестно") is None


def _entries(path):
    txt = open(path, encoding="utf-8").read()
    return txt, txt.split("\n\n")[1:]


def test_kataloge_th_ru():
    for lang, n in (("ru", 3), ("th", 1)):
        txt, ents = _entries(os.path.join(PO, f"{lang}.po"))
        assert f"nplurals={n};" in txt
        assert "#, fuzzy" not in txt
        for e in ents:
            if "msgid_plural" in e:
                forms = re.findall(r"^msgstr\[(\d)\] \"(.*)\"$", e, re.M)
                assert [int(i) for i, _s in forms] == list(range(n)), e
                assert all(s for _i, s in forms), e
            else:
                assert not re.search(r'^msgstr ""\n(?!")', e + "\n", re.M), e
