# Aufgaben (Test-Aufgabe anlegen/abhaken/loeschen), Termine, Dienste, Papierkorb
S = {}


def tasks(g):
    g["win"].show_root(("tasks",))
    g["store"].add_task("HK Linux Testaufgabe", due=None, prio=1)


def shot_tasks(g):
    st = g["store"]
    t = next((x for x in st.tasks if x["text"] == "HK Linux Testaufgabe"), None)
    S["t"] = t
    print("Aufgabe angelegt:", bool(t), "offen:", len(st.tasks), flush=True)
    g["shot"](g["out"] + "/50-aufgaben.png")
    if t:
        st.complete_task(t)


def check_done(g):
    st = g["store"]
    t = S.get("t")
    print("erledigt-Liste enthält Test:", any(x["id"] == t["id"] for x in st.done_tasks) if t else None, flush=True)
    if t:
        st.delete_task(t["id"])


def events(g):
    g["win"].show_root(("events",))


def shot_events(g):
    g["shot"](g["out"] + "/51-termine.png")
    g["win"].show_root(("services",))


def shot_services(g):
    g["shot"](g["out"] + "/52-dienste.png")
    g["win"].trash_dialog()


def shot_trash(g):
    g["shot"](g["out"] + "/53-papierkorb.png")
    print("Aufgaben am Ende:", [x["text"] for x in g["store"].tasks], flush=True)


steps = [(2500, tasks), (2000, shot_tasks), (2000, check_done), (1500, events), (3000, shot_events), (3000, shot_services), (2000, shot_trash)]
