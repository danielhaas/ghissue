"""Settings dialog: client ID, login, repo selection."""

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk

from .. import config
from ..api import GitHubAPI
from ..keyring import clear_token, get_token, is_logged_in, store_token
from ..main import run_in_background
from .device_flow import DeviceFlowDialog


class SettingsDialog(Gtk.Dialog):
    def __init__(self, app):
        super().__init__(
            title="Settings",
            transient_for=None,
            modal=True,
        )
        self.set_default_size(420, -1)
        self.set_resizable(False)
        self._app = app
        self._cfg = config.load()

        box = self.get_content_area()
        box.set_spacing(10)
        box.set_margin_start(16)
        box.set_margin_end(16)
        box.set_margin_top(12)
        box.set_margin_bottom(12)

        # ── Client ID ──
        box.add(Gtk.Label(label="Client ID:", xalign=0))
        self._client_id_entry = Gtk.Entry()
        self._client_id_entry.set_text(self._cfg.get("client_id", ""))
        self._client_id_entry.connect("changed", self._on_client_id_changed)
        box.add(self._client_id_entry)

        box.add(Gtk.Separator())

        # ── Login status ──
        self._login_label = Gtk.Label(xalign=0)
        box.add(self._login_label)

        self._login_btn = Gtk.Button()
        self._login_btn.connect("clicked", self._on_login_toggle)
        box.add(self._login_btn)

        box.add(Gtk.Separator())

        # ── Repository ──
        box.add(Gtk.Label(label="Repository:", xalign=0))

        repo_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self._repo_label = Gtk.Label(xalign=0)
        self._repo_label.set_hexpand(True)
        repo_box.add(self._repo_label)

        self._repo_btn = Gtk.Button(label="Select...")
        self._repo_btn.connect("clicked", self._on_select_repo)
        repo_box.add(self._repo_btn)
        box.add(repo_box)

        # Close button
        self.add_button("Close", Gtk.ResponseType.CLOSE)

        self._update_login_ui()
        self._update_repo_ui()
        self.show_all()

    # ── Client ID ──

    def _on_client_id_changed(self, entry):
        self._cfg["client_id"] = entry.get_text().strip()
        config.save(self._cfg)

    # ── Login ──

    def _update_login_ui(self):
        if is_logged_in():
            self._login_label.set_text("Status: Logged in")
            self._login_btn.set_label("Logout")
        else:
            self._login_label.set_text("Status: Not logged in")
            self._login_btn.set_label("Login")

    def _on_login_toggle(self, _btn):
        if is_logged_in():
            clear_token()
            self._update_login_ui()
        else:
            self._start_login()

    def _start_login(self):
        client_id = self._cfg.get("client_id", "").strip()
        if not client_id:
            self._show_error("Please enter a Client ID first.")
            return

        self._login_btn.set_sensitive(False)
        self._login_btn.set_label("Requesting code...")

        def _request():
            return self._app.api.request_device_code(client_id)

        def _on_code(resp):
            self._login_btn.set_sensitive(True)
            self._login_btn.set_label("Login")
            dlg = DeviceFlowDialog(self._app, resp)
            result = dlg.run()
            token = dlg.token
            dlg.destroy()
            if result == Gtk.ResponseType.OK and token:
                store_token(token)
            self._update_login_ui()

        def _request_and_callback():
            try:
                resp = _request()
                GLib.idle_add(_on_code, resp)
            except Exception as e:
                GLib.idle_add(self._on_login_request_error, str(e))

        import threading
        threading.Thread(target=_request_and_callback, daemon=True).start()

    def _on_login_request_error(self, msg):
        self._login_btn.set_sensitive(True)
        self._login_btn.set_label("Login")
        self._show_error(f"Failed to start login:\n{msg}")

    # ── Repository selection ──

    def _update_repo_ui(self):
        repo = config.get_repo(self._cfg)
        if repo:
            self._repo_label.set_text(f"{repo[0]}/{repo[1]}")
        else:
            self._repo_label.set_text("(none)")
        self._repo_btn.set_sensitive(is_logged_in())

    def _on_select_repo(self, _btn):
        token = get_token()
        if not token:
            self._show_error("Please log in first.")
            return

        self._repo_btn.set_sensitive(False)
        self._repo_btn.set_label("Loading...")

        def _fetch():
            return self._app.api.list_repos(token)

        def _on_repos(repos):
            self._repo_btn.set_sensitive(True)
            self._repo_btn.set_label("Select...")
            if not repos:
                self._show_error("No repositories found.")
                return
            self._show_repo_picker(repos)

        def _fetch_and_callback():
            try:
                repos = _fetch()
                GLib.idle_add(_on_repos, repos)
            except Exception as e:
                GLib.idle_add(self._on_repo_fetch_error, str(e))

        import threading
        threading.Thread(target=_fetch_and_callback, daemon=True).start()

    def _on_repo_fetch_error(self, msg):
        self._repo_btn.set_sensitive(True)
        self._repo_btn.set_label("Select...")
        self._show_error(f"Failed to load repositories:\n{msg}")

    def _show_repo_picker(self, repos):
        dlg = Gtk.Dialog(
            title="Select Repository",
            transient_for=self,
            modal=True,
        )
        dlg.set_default_size(400, 400)

        box = dlg.get_content_area()

        # Search entry
        search = Gtk.SearchEntry()
        search.set_placeholder_text("Filter repositories...")
        box.add(search)

        scroll = Gtk.ScrolledWindow()
        scroll.set_vexpand(True)
        listbox = Gtk.ListBox()
        listbox.set_selection_mode(Gtk.SelectionMode.SINGLE)
        scroll.add(listbox)
        box.add(scroll)

        rows = []
        for repo in repos:
            row = Gtk.ListBoxRow()
            lbl = Gtk.Label(label=repo.full_name, xalign=0)
            lbl.set_margin_start(8)
            lbl.set_margin_end(8)
            lbl.set_margin_top(4)
            lbl.set_margin_bottom(4)
            row.add(lbl)
            row._repo = repo
            listbox.add(row)
            rows.append((row, repo.full_name.lower()))

        def _filter(entry):
            text = entry.get_text().lower()
            for row, name in rows:
                row.set_visible(text in name)

        search.connect("search-changed", _filter)

        dlg.add_button("Cancel", Gtk.ResponseType.CANCEL)
        dlg.add_button("Select", Gtk.ResponseType.OK)

        dlg.show_all()
        result = dlg.run()

        if result == Gtk.ResponseType.OK:
            selected = listbox.get_selected_row()
            if selected:
                repo = selected._repo
                self._cfg["repo_owner"] = repo.owner
                self._cfg["repo_name"] = repo.name
                config.save(self._cfg)
                self._update_repo_ui()

        dlg.destroy()

    # ── Helpers ──

    def _show_error(self, msg):
        dlg = Gtk.MessageDialog(
            transient_for=self,
            modal=True,
            message_type=Gtk.MessageType.ERROR,
            buttons=Gtk.ButtonsType.OK,
            text=msg,
        )
        dlg.run()
        dlg.destroy()
