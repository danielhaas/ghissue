"""Entry point: system tray icon and GLib main loop."""

import os
import signal
import sys
import threading

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("AppIndicator3", "0.1")
gi.require_version("Notify", "0.7")

from gi.repository import AppIndicator3, GLib, Gtk, Notify

from . import config
from .api import GitHubAPI
from .keyring import get_token, is_logged_in
from .network import NetworkMonitor
from .queue import IssueQueue

_ICON_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "resources", "ghissue-icon.svg",
)
_APP_ID = "com.github.ghissue"


def run_in_background(func, callback=None):
    """Run *func* in a daemon thread; post *callback(result)* via GLib.idle_add."""
    def _worker():
        result = func()
        if callback:
            GLib.idle_add(callback, result)
    t = threading.Thread(target=_worker, daemon=True)
    t.start()
    return t


class Application:
    def __init__(self):
        Notify.init("ghissue")
        self.api = GitHubAPI()
        self.queue = IssueQueue()
        self.cfg = config.load()

        # Build tray icon
        self.indicator = AppIndicator3.Indicator.new(
            _APP_ID,
            _ICON_PATH,
            AppIndicator3.IndicatorCategory.APPLICATION_STATUS,
        )
        self.indicator.set_status(AppIndicator3.IndicatorStatus.ACTIVE)

        self._build_menu()

        # Network monitor — drain queue when connectivity returns
        self.net = NetworkMonitor(on_network_available=self._on_network_up)

        # Drain queue on startup if non-empty
        if self.queue.count() > 0:
            self._try_drain()

    # ── Menu ──

    def _build_menu(self):
        menu = Gtk.Menu()

        self.create_item = Gtk.MenuItem(label="Create Issue...")
        self.create_item.connect("activate", self._on_create_issue)
        menu.append(self.create_item)

        menu.append(Gtk.SeparatorMenuItem())

        settings_item = Gtk.MenuItem(label="Settings...")
        settings_item.connect("activate", self._on_settings)
        menu.append(settings_item)

        menu.append(Gtk.SeparatorMenuItem())

        self.queue_item = Gtk.MenuItem(label="Queue: 0 pending")
        self.queue_item.set_sensitive(False)
        menu.append(self.queue_item)

        menu.append(Gtk.SeparatorMenuItem())

        quit_item = Gtk.MenuItem(label="Quit")
        quit_item.connect("activate", self._on_quit)
        menu.append(quit_item)

        menu.show_all()
        self._refresh_menu_state()
        self.indicator.set_menu(menu)

    def _refresh_menu_state(self):
        logged_in = is_logged_in()
        repo = config.get_repo(self.cfg)
        self.create_item.set_sensitive(logged_in and repo is not None)

        n = self.queue.count()
        if n > 0:
            self.queue_item.set_label(f"Queue: {n} pending")
            self.queue_item.show()
        else:
            self.queue_item.hide()

    def refresh(self):
        """Reload config and refresh menu state (safe from any thread via idle_add)."""
        self.cfg = config.load()
        self._refresh_menu_state()

    # ── Actions ──

    def _on_create_issue(self, _widget):
        from .dialogs.create_issue import CreateIssueDialog
        dlg = CreateIssueDialog(self)
        dlg.run()
        dlg.destroy()
        self._refresh_menu_state()

    def _on_settings(self, _widget):
        from .dialogs.settings import SettingsDialog
        dlg = SettingsDialog(self)
        dlg.run()
        dlg.destroy()
        self.refresh()

    def _on_quit(self, _widget):
        Notify.uninit()
        Gtk.main_quit()

    # ── Queue draining ──

    def _on_network_up(self):
        if self.queue.count() > 0:
            self._try_drain()

    def _try_drain(self):
        token = get_token()
        if not token:
            return

        def _drain():
            return self.queue.drain(self.api, token)

        def _on_drained(result):
            if result.submitted > 0:
                self._notify(
                    "Issues submitted",
                    f"{result.submitted} queued issue(s) submitted.",
                )
            self._refresh_menu_state()

        run_in_background(_drain, _on_drained)

    def _notify(self, title, body):
        n = Notify.Notification.new(title, body, "dialog-information")
        try:
            n.show()
        except Exception:
            pass

    # ── Main loop ──

    def run(self):
        # Allow Ctrl+C to exit
        signal.signal(signal.SIGINT, signal.SIG_DFL)
        Gtk.main()


def main():
    app = Application()
    app.run()


if __name__ == "__main__":
    main()
