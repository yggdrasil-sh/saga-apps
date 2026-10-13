icons = open("/tmp/hk-linux/hlidskjalf/icons.txt").read().split()
def chk(g):
    from gi.repository import Gtk
    th = Gtk.IconTheme.get_for_display(g["win"].get_display())
    print("FEHLT:", [i for i in icons if not th.has_icon(i)], flush=True)
steps = [(800, chk)]
