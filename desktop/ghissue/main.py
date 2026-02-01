"""Entry point: DBus service daemon and GLib main loop."""

import argparse
import json
import os
import signal
import sys
import threading

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Notify", "0.7")

from gi.repository import Gio, GLib, Gtk, Notify

from . import config
from .api import GitHubAPI
from .keyring import get_token, is_logged_in
from .network import NetworkMonitor
from .queue import IssueQueue

_APP_ID = "com.github.ghissue"
_DBUS_PATH = "/com/github/ghissue"

_DBUS_XML = """
<node>
  <interface name="com.github.ghissue">
    <method name="CreateIssue">
      <arg type="s" name="owner" direction="in"/>
      <arg type="s" name="repo" direction="in"/>
    </method>
    <method name="OpenSettings"/>
    <method name="GetRepos">
      <arg type="s" name="json" direction="out"/>
    </method>
    <method name="GetQueueCount">
      <arg type="i" name="count" direction="out"/>
    </method>
    <method name="Quit"/>
    <signal name="ReposChanged"/>
  </interface>
</node>
"""


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
        self._connection = None

        # Network monitor — drain queue when connectivity returns
        self.net = NetworkMonitor(on_network_available=self._on_network_up)

        # Drain queue on startup if non-empty
        if self.queue.count() > 0:
            self._try_drain()

        # Register DBus service
        self._dbus_owner_id = Gio.bus_own_name(
            Gio.BusType.SESSION,
            _APP_ID,
            Gio.BusNameOwnerFlags.NONE,
            self._on_bus_acquired,
            None,
            None,
        )

    # ── DBus ──

    def _on_bus_acquired(self, connection, name):
        self._connection = connection
        introspection = Gio.DBusNodeInfo.new_for_xml(_DBUS_XML)
        connection.register_object(
            _DBUS_PATH,
            introspection.interfaces[0],
            self._on_dbus_method_call,
            None,
            None,
        )

    def _emit_repos_changed(self):
        if self._connection:
            self._connection.emit_signal(
                None,
                _DBUS_PATH,
                _APP_ID,
                "ReposChanged",
                None,
            )

    def _on_dbus_method_call(self, connection, sender, object_path,
                             interface_name, method_name, parameters,
                             invocation):
        if method_name == "CreateIssue":
            owner = parameters.unpack()[0]
            repo = parameters.unpack()[1]
            GLib.idle_add(self._on_create_issue, owner, repo)
            invocation.return_value(None)
        elif method_name == "OpenSettings":
            GLib.idle_add(self._on_settings)
            invocation.return_value(None)
        elif method_name == "GetRepos":
            repos = config.get_repos(self.cfg)
            payload = json.dumps([
                {"owner": r["owner"], "name": r["name"], "color": r.get("color", "#238636")}
                for r in repos
            ])
            invocation.return_value(GLib.Variant("(s)", (payload,)))
        elif method_name == "GetQueueCount":
            n = self.queue.count()
            invocation.return_value(GLib.Variant("(i)", (n,)))
        elif method_name == "Quit":
            invocation.return_value(None)
            GLib.idle_add(self._on_quit)
        else:
            invocation.return_dbus_error(
                "org.freedesktop.DBus.Error.UnknownMethod",
                f"No such method: {method_name}",
            )

    # ── Actions ──

    def _on_create_issue(self, owner, repo):
        if not is_logged_in():
            self._notify("ghissue", "Please log in first in Settings.")
            return
        if not config.find_repo(self.cfg, owner, repo):
            self._notify("ghissue", "Repository not found in configuration.")
            return
        from .dialogs.create_issue import CreateIssueDialog
        dlg = CreateIssueDialog(self, owner, repo)
        dlg.run()
        dlg.destroy()

    def _on_settings(self):
        from .dialogs.settings import SettingsDialog
        dlg = SettingsDialog(self)
        dlg.run()
        dlg.destroy()
        self.cfg = config.load()
        self._emit_repos_changed()

    def _on_quit(self):
        if self._dbus_owner_id:
            Gio.bus_unown_name(self._dbus_owner_id)
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

        run_in_background(_drain, _on_drained)

    def _notify(self, title, body):
        n = Notify.Notification.new(title, body, "dialog-information")
        try:
            n.show()
        except Exception:
            pass

    # ── Main loop ──

    def run(self):
        signal.signal(signal.SIGINT, signal.SIG_DFL)
        Gtk.main()


def _send_dbus_call(method, params=None, reply_type=None):
    """Send a DBus call to the running daemon and exit."""
    try:
        bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        bus.call_sync(
            _APP_ID,
            _DBUS_PATH,
            _APP_ID,
            method,
            params,
            reply_type,
            Gio.DBusCallFlags.NONE,
            5000,
            None,
        )
    except GLib.Error as e:
        print(f"ghissue: could not reach daemon: {e.message}", file=sys.stderr)
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="ghissue — quick GitHub issue creator")
    parser.add_argument(
        "--create",
        action="store_true",
        help="Open the Create Issue dialog on the running daemon and exit",
    )
    args = parser.parse_args()

    if args.create:
        # Use first configured repo
        cfg = config.load()
        repo = config.get_repo(cfg)
        if repo:
            _send_dbus_call(
                "CreateIssue",
                GLib.Variant("(ss)", (repo[0], repo[1])),
            )
        else:
            print("ghissue: no repository configured", file=sys.stderr)
            sys.exit(1)
        return

    app = Application()
    app.run()


if __name__ == "__main__":
    main()
