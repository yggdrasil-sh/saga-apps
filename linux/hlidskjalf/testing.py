"""Testmodus (nur mit HK_TEST): Skript-Schritte mit Wartezeiten und Bildschirmfotos des Fensters."""
import ctypes
import os
import sys
import traceback

from gi.repository import GLib, Gtk

_gtk = None


def _lib():
    global _gtk
    if _gtk is None:
        _gtk = ctypes.CDLL("libgtk-4.so.1")
        vp = ctypes.c_void_p
        for name, res, args in (("gtk_widget_paintable_new", vp, [vp]), ("gtk_snapshot_new", vp, []),
                                ("gdk_paintable_snapshot", None, [vp, vp, ctypes.c_double, ctypes.c_double]),
                                ("gtk_snapshot_free_to_node", vp, [vp]), ("gtk_widget_get_native", vp, [vp]),
                                ("gtk_native_get_renderer", vp, [vp]), ("gsk_renderer_render_texture", vp, [vp, vp, vp]),
                                ("gdk_texture_save_to_png", ctypes.c_bool, [vp, ctypes.c_char_p]), ("gsk_render_node_unref", None, [vp]),
                                ("g_object_unref", None, [vp])):
            f = getattr(_gtk, name)
            f.restype = res
            f.argtypes = args
    return _gtk


def _ptr(obj):
    get = ctypes.pythonapi.PyCapsule_GetPointer
    get.restype = ctypes.c_void_p
    get.argtypes = [ctypes.py_object, ctypes.c_char_p]
    return get(obj.__gpointer__, None)


def shot(win, path, widget=None):
    """Widget (Standard: Fensterinhalt) als PNG - ueber die C-API, PyGObject kann Render-Knoten nicht uebergeben"""
    w = widget or win
    L = _lib()
    W, H = w.get_width(), w.get_height()
    wp = _ptr(w)
    pt = L.gtk_widget_paintable_new(wp)
    sn = L.gtk_snapshot_new()
    L.gdk_paintable_snapshot(pt, sn, W, H)
    node = L.gtk_snapshot_free_to_node(sn)
    if not node:
        print("SHOT leer", path, flush=True)
        return
    rnd = L.gtk_native_get_renderer(L.gtk_widget_get_native(wp))
    rect = (ctypes.c_float * 4)(0, 0, W, H)                      # graphene_rect_t: ganzer Bereich, nicht nur Inhalt
    tex = L.gsk_renderer_render_texture(rnd, node, ctypes.cast(rect, ctypes.c_void_p))
    L.gdk_texture_save_to_png(tex, path.encode())
    L.gsk_render_node_unref(node)
    L.g_object_unref(tex)
    L.g_object_unref(pt)
    print("SHOT", os.path.basename(path), W, H, flush=True)


def run(win, script):
    """Das Skript definiert steps = [(ms, funktion), ...]; jede Funktion bekommt das Kontext-Woerterbuch."""
    from .store import store
    g = {"win": win, "store": store, "Gtk": Gtk, "GLib": GLib, "shot": lambda p, w=None: shot(win, p, w), "out": os.environ.get("HK_OUT", "/tmp")}
    try:
        with open(script, encoding="utf-8") as f:
            exec(compile(f.read(), script, "exec"), g)  # noqa: S102
    except Exception:  # noqa: BLE001
        traceback.print_exc()
        win.get_application().quit()
        return
    steps = list(g.get("steps", []))

    def nxt():
        if not steps:
            print("TEST FERTIG", flush=True)
            win.get_application().quit()
            return False
        ms, fn = steps.pop(0)

        def go():
            try:
                fn(g)
            except Exception:  # noqa: BLE001
                traceback.print_exc(file=sys.stdout)
            nxt()
            return False
        GLib.timeout_add(ms, go)
        return False
    nxt()
