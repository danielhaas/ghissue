"""Settings dialog: client ID, login, multi-repo management."""

import threading

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, GLib, Gtk

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
        self.set_default_size(460, -1)
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

        # ── Repositories ──
        box.add(Gtk.Label(label="Repositories:", xalign=0))

        self._repo_listbox = Gtk.ListBox()
        self._repo_listbox.set_selection_mode(Gtk.SelectionMode.NONE)
        box.add(self._repo_listbox)

        self._add_repo_btn = Gtk.Button(label="Add Repository...")
        self._add_repo_btn.connect("clicked", self._on_add_repo)
        box.add(self._add_repo_btn)

        # Close button
        self.add_button("Close", Gtk.ResponseType.CLOSE)

        self._update_login_ui()
        self._rebuild_repo_list()
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
        self._add_repo_btn.set_sensitive(is_logged_in())

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

        threading.Thread(target=_request_and_callback, daemon=True).start()

    def _on_login_request_error(self, msg):
        self._login_btn.set_sensitive(True)
        self._login_btn.set_label("Login")
        self._show_error(f"Failed to start login:\n{msg}")

    # ── Repository list ──

    def _rebuild_repo_list(self):
        for child in self._repo_listbox.get_children():
            self._repo_listbox.remove(child)

        repos = config.get_repos(self._cfg)
        if not repos:
            row = Gtk.ListBoxRow()
            row.set_selectable(False)
            lbl = Gtk.Label(label="No repositories configured.", xalign=0)
            lbl.set_margin_start(8)
            lbl.set_margin_top(4)
            lbl.set_margin_bottom(4)
            lbl.set_sensitive(False)
            row.add(lbl)
            self._repo_listbox.add(row)
        else:
            for repo in repos:
                self._repo_listbox.add(self._make_repo_row(repo))

        self._repo_listbox.show_all()

    def _make_repo_row(self, repo):
        row = Gtk.ListBoxRow()
        row.set_selectable(False)
        hbox = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        hbox.set_margin_start(8)
        hbox.set_margin_end(8)
        hbox.set_margin_top(4)
        hbox.set_margin_bottom(4)

        # Colored dot
        color_dot = Gtk.DrawingArea()
        color_dot.set_size_request(16, 16)
        color_hex = repo.get("color", "#238636")

        def _draw_dot(widget, cr):
            rgba = Gdk.RGBA()
            rgba.parse(color_hex)
            cr.set_source_rgba(rgba.red, rgba.green, rgba.blue, rgba.alpha)
            w = widget.get_allocated_width()
            h = widget.get_allocated_height()
            cr.arc(w / 2, h / 2, min(w, h) / 2, 0, 2 * 3.14159)
            cr.fill()

        color_dot.connect("draw", _draw_dot)
        hbox.pack_start(color_dot, False, False, 0)

        # Repo name
        lbl = Gtk.Label(label=f"{repo['owner']}/{repo['name']}", xalign=0)
        lbl.set_hexpand(True)
        hbox.pack_start(lbl, True, True, 0)

        # Configure button
        config_btn = Gtk.Button(label="Configure...")
        config_btn.connect("clicked", lambda _b: self._on_configure_repo(repo))
        hbox.pack_start(config_btn, False, False, 0)

        # Remove button
        remove_btn = Gtk.Button(label="Remove")
        remove_btn.get_style_context().add_class("destructive-action")
        remove_btn.connect("clicked", lambda _b: self._on_remove_repo(repo))
        hbox.pack_start(remove_btn, False, False, 0)

        row.add(hbox)
        return row

    def _on_add_repo(self, _btn):
        token = get_token()
        if not token:
            self._show_error("Please log in first.")
            return

        self._add_repo_btn.set_sensitive(False)
        self._add_repo_btn.set_label("Loading...")

        def _fetch():
            return self._app.api.list_repos(token)

        def _on_repos(repos):
            self._add_repo_btn.set_sensitive(True)
            self._add_repo_btn.set_label("Add Repository...")
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

        threading.Thread(target=_fetch_and_callback, daemon=True).start()

    def _on_repo_fetch_error(self, msg):
        self._add_repo_btn.set_sensitive(True)
        self._add_repo_btn.set_label("Add Repository...")
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
            # Skip already-configured repos
            if config.find_repo(self._cfg, repo.owner, repo.name):
                continue
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
        dlg.add_button("Add", Gtk.ResponseType.OK)

        dlg.show_all()
        result = dlg.run()

        if result == Gtk.ResponseType.OK:
            selected = listbox.get_selected_row()
            if selected:
                repo = selected._repo
                config.add_repo(self._cfg, repo.owner, repo.name)
                config.save(self._cfg)
                self._rebuild_repo_list()

        dlg.destroy()

    def _on_remove_repo(self, repo):
        config.remove_repo(self._cfg, repo["owner"], repo["name"])
        config.save(self._cfg)
        self._rebuild_repo_list()

    def _on_configure_repo(self, repo):
        dlg = RepoConfigDialog(self, self._app, repo)
        dlg.run()
        dlg.destroy()
        config.save(self._cfg)
        self._rebuild_repo_list()

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


class RepoConfigDialog(Gtk.Dialog):
    """Sub-dialog for configuring a repo's color and default labels."""

    def __init__(self, parent, app, repo):
        super().__init__(
            title=f"Configure {repo['owner']}/{repo['name']}",
            transient_for=parent,
            modal=True,
        )
        self.set_default_size(400, -1)
        self.set_resizable(False)
        self._app = app
        self._repo = repo

        box = self.get_content_area()
        box.set_spacing(10)
        box.set_margin_start(16)
        box.set_margin_end(16)
        box.set_margin_top(12)
        box.set_margin_bottom(12)

        # ── Color picker ──
        box.add(Gtk.Label(label="Color:", xalign=0))

        color_grid = Gtk.FlowBox()
        color_grid.set_selection_mode(Gtk.SelectionMode.NONE)
        color_grid.set_max_children_per_line(8)
        color_grid.set_row_spacing(6)
        color_grid.set_column_spacing(6)

        self._color_buttons = []
        current_color = repo.get("color", config.PRESET_COLORS[3])

        for color in config.PRESET_COLORS:
            btn = Gtk.ToggleButton()
            btn.set_size_request(36, 36)

            active = (color == current_color)
            btn.set_active(active)

            css = (
                f"button {{ background: {color}; border: 2px solid "
                f"{'white' if active else 'transparent'}; border-radius: 18px; "
                f"min-width: 32px; min-height: 32px; padding: 0; }}"
                f"button:checked {{ border: 3px solid white; "
                f"box-shadow: 0 0 0 1px {color}; }}"
            )
            provider = Gtk.CssProvider()
            provider.load_from_data(css.encode())
            btn.get_style_context().add_provider(
                provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
            )

            btn.connect("toggled", self._on_color_toggled, color)
            self._color_buttons.append((btn, color))
            color_grid.add(btn)

        box.add(color_grid)

        box.add(Gtk.Separator())

        # ── Default labels ──
        box.add(Gtk.Label(label="Default labels:", xalign=0))

        self._labels_flow = Gtk.FlowBox()
        self._labels_flow.set_selection_mode(Gtk.SelectionMode.NONE)
        self._labels_flow.set_max_children_per_line(10)
        self._labels_flow.set_row_spacing(4)
        self._labels_flow.set_column_spacing(4)
        box.add(self._labels_flow)

        self._labels_spinner = Gtk.Spinner()
        box.add(self._labels_spinner)

        self._label_buttons: list[tuple[Gtk.ToggleButton, str]] = []

        self.add_button("Done", Gtk.ResponseType.CLOSE)

        self.show_all()
        self._labels_spinner.hide()
        self._fetch_labels()

    def _on_color_toggled(self, toggled_btn, color):
        if not toggled_btn.get_active():
            return
        # Radio behavior: uncheck others
        for btn, c in self._color_buttons:
            if btn is not toggled_btn:
                btn.set_active(False)
        self._repo["color"] = color

    def _fetch_labels(self):
        token = get_token()
        if not token:
            return

        self._labels_spinner.show()
        self._labels_spinner.start()

        owner = self._repo["owner"]
        name = self._repo["name"]

        def _fetch():
            return self._app.api.list_labels(token, owner, name)

        def _on_labels(labels):
            self._labels_spinner.stop()
            self._labels_spinner.hide()
            self._populate_labels(labels)

        def _on_error(msg):
            self._labels_spinner.stop()
            self._labels_spinner.hide()

        def _run():
            try:
                labels = _fetch()
                GLib.idle_add(_on_labels, labels)
            except Exception as e:
                GLib.idle_add(_on_error, str(e))

        threading.Thread(target=_run, daemon=True).start()

    def _populate_labels(self, labels):
        for child in self._labels_flow.get_children():
            self._labels_flow.remove(child)
        self._label_buttons.clear()

        if not labels:
            self._labels_flow.hide()
            return

        defaults = set(self._repo.get("default_labels", []))

        for label in labels:
            btn = Gtk.ToggleButton(label=label.name)
            btn.set_active(label.name in defaults)
            btn.get_style_context().add_class("label-chip")

            # Per-label color CSS
            r = int(label.color[0:2], 16)
            g = int(label.color[2:4], 16)
            b = int(label.color[4:6], 16)
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255
            fg = "#000000" if lum > 0.5 else "#ffffff"
            css = (
                f"button.label-chip {{"
                f"  background: #{label.color}; color: {fg};"
                f"  border: none; border-radius: 12px;"
                f"  padding: 2px 10px; min-height: 0;"
                f"}}"
                f"button.label-chip:checked {{"
                f"  background: #{label.color}; color: {fg};"
                f"  border: 2px solid {fg};"
                f"}}"
            )
            provider = Gtk.CssProvider()
            provider.load_from_data(css.encode())
            btn.get_style_context().add_provider(
                provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
            )

            btn.connect("toggled", self._on_label_toggled)
            self._label_buttons.append((btn, label.name))
            self._labels_flow.add(btn)

        self._labels_flow.show_all()

    def _on_label_toggled(self, _btn):
        self._repo["default_labels"] = [
            name for btn, name in self._label_buttons if btn.get_active()
        ]
