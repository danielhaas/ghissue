"""Settings dialog: accounts (GitHub, Gitea, MindBlown) and multi-repo management."""

import threading
import webbrowser

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, GLib, Gtk

from .. import config
from ..api import GiteaAPI, MindBlownAPI
from ..keyring import clear_token, get_token, is_logged_in, store_token
from ..main import run_in_background
from .device_flow import DeviceFlowDialog

_KIND_TITLES = {"github": "GitHub", "gitea": "Gitea", "mindblown": "MindBlown"}


def _show_error(parent, msg):
    dlg = Gtk.MessageDialog(
        transient_for=parent,
        modal=True,
        message_type=Gtk.MessageType.ERROR,
        buttons=Gtk.ButtonsType.OK,
        text=msg,
    )
    dlg.run()
    dlg.destroy()


class SettingsDialog(Gtk.Dialog):
    def __init__(self, app):
        super().__init__(
            title="Settings",
            transient_for=None,
            modal=True,
        )
        self.set_default_size(520, -1)
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
        box.add(Gtk.Label(label="GitHub Client ID:", xalign=0))
        self._client_id_entry = Gtk.Entry()
        self._client_id_entry.set_text(self._cfg.get("client_id", ""))
        self._client_id_entry.connect("changed", self._on_client_id_changed)
        box.add(self._client_id_entry)

        box.add(Gtk.Separator())

        # ── Accounts ──
        box.add(Gtk.Label(label="Accounts:", xalign=0))

        self._account_listbox = Gtk.ListBox()
        self._account_listbox.set_selection_mode(Gtk.SelectionMode.NONE)
        box.add(self._account_listbox)

        add_account_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        add_gitea_btn = Gtk.Button(label="Add Gitea Account...")
        add_gitea_btn.connect("clicked", lambda _b: self._on_add_account("gitea"))
        add_account_box.pack_start(add_gitea_btn, True, True, 0)
        add_mb_btn = Gtk.Button(label="Add MindBlown Account...")
        add_mb_btn.connect("clicked", lambda _b: self._on_add_account("mindblown"))
        add_account_box.pack_start(add_mb_btn, True, True, 0)
        box.add(add_account_box)

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

        self._rebuild_account_list()
        self._rebuild_repo_list()
        self.show_all()

    # ── Client ID ──

    def _on_client_id_changed(self, entry):
        self._cfg["client_id"] = entry.get_text().strip()
        config.save(self._cfg)

    # ── Accounts ──

    def _logged_in_accounts(self) -> list[dict]:
        return [a for a in config.get_accounts(self._cfg) if is_logged_in(a["id"])]

    def _rebuild_account_list(self):
        for child in self._account_listbox.get_children():
            self._account_listbox.remove(child)
        for account in config.get_accounts(self._cfg):
            self._account_listbox.add(self._make_account_row(account))
        self._account_listbox.show_all()
        self._add_repo_btn.set_sensitive(bool(self._logged_in_accounts()))

    def _make_account_row(self, account):
        row = Gtk.ListBoxRow()
        row.set_selectable(False)
        hbox = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        hbox.set_margin_start(8)
        hbox.set_margin_end(8)
        hbox.set_margin_top(4)
        hbox.set_margin_bottom(4)

        logged_in = is_logged_in(account["id"])
        if logged_in:
            status = f"as {account['username']}" if account.get("username") else "logged in"
        else:
            status = "not logged in"
        lbl = Gtk.Label(xalign=0)
        lbl.set_markup(
            f"<b>{_KIND_TITLES[account['kind']]}</b> · "
            f"{GLib.markup_escape_text(account['id'])} "
            f'<span size="small" foreground="gray">{GLib.markup_escape_text(status)}</span>'
        )
        lbl.set_hexpand(True)
        hbox.pack_start(lbl, True, True, 0)

        if account["kind"] == "github":
            login_btn = Gtk.Button(label="Logout" if logged_in else "Login")
            login_btn.connect("clicked", self._on_github_login_toggle)
            hbox.pack_start(login_btn, False, False, 0)
        else:
            relogin_btn = Gtk.Button(label="Log In Again..." if logged_in else "Log In...")
            relogin_btn.connect(
                "clicked", lambda _b: self._on_add_account(account["kind"], account),
            )
            hbox.pack_start(relogin_btn, False, False, 0)

            remove_btn = Gtk.Button(label="Remove")
            remove_btn.get_style_context().add_class("destructive-action")
            remove_btn.connect("clicked", lambda _b: self._on_remove_account(account))
            hbox.pack_start(remove_btn, False, False, 0)

        row.add(hbox)
        return row

    def _on_add_account(self, kind, account=None):
        dlg = AccountDialog(self, kind, account)
        result = dlg.run()
        dlg.destroy()
        if result == Gtk.ResponseType.OK:
            self._cfg = config.load()  # AccountDialog saved the new account
            self._rebuild_account_list()

    def _on_remove_account(self, account):
        confirm = Gtk.MessageDialog(
            transient_for=self,
            modal=True,
            message_type=Gtk.MessageType.QUESTION,
            buttons=Gtk.ButtonsType.OK_CANCEL,
            text=f"Remove {account['id']} and its repositories?",
        )
        result = confirm.run()
        confirm.destroy()
        if result != Gtk.ResponseType.OK:
            return
        clear_token(account["id"])
        config.remove_account(self._cfg, account["id"])
        config.save(self._cfg)
        self._rebuild_account_list()
        self._rebuild_repo_list()

    def _on_github_login_toggle(self, btn):
        if is_logged_in(config.GITHUB_ACCOUNT_ID):
            clear_token(config.GITHUB_ACCOUNT_ID)
            self._rebuild_account_list()
        else:
            self._start_github_login(btn)

    def _start_github_login(self, btn):
        client_id = self._cfg.get("client_id", "").strip()
        if not client_id:
            self._show_error("Please enter a Client ID first.")
            return

        btn.set_sensitive(False)
        btn.set_label("Requesting code...")

        def _request():
            return self._app.api.request_device_code(client_id)

        def _on_code(resp):
            dlg = DeviceFlowDialog(self._app, resp)
            result = dlg.run()
            token = dlg.token
            dlg.destroy()
            if result == Gtk.ResponseType.OK and token:
                store_token(config.GITHUB_ACCOUNT_ID, token)
            self._rebuild_account_list()

        def _on_error(msg):
            btn.set_sensitive(True)
            btn.set_label("Login")
            self._show_error(f"Failed to start login:\n{msg}")

        def _request_and_callback():
            try:
                resp = _request()
                GLib.idle_add(_on_code, resp)
            except Exception as e:
                GLib.idle_add(_on_error, str(e))

        threading.Thread(target=_request_and_callback, daemon=True).start()

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
        lbl = Gtk.Label(label=config.repo_display_name(self._cfg, repo), xalign=0)
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
        clients = [
            (a, self._app.client(a["id"], self._cfg))
            for a in self._logged_in_accounts()
        ]
        if not clients:
            self._show_error("Please log in to an account first.")
            return

        self._add_repo_btn.set_sensitive(False)
        self._add_repo_btn.set_label("Loading...")

        def _fetch():
            """Returns ([(account, repo)], [error message])."""
            found, errors = [], []
            for account, (api, token) in clients:
                try:
                    found += [(account, r) for r in api.list_repos(token)]
                except Exception as e:
                    errors.append(f"{account['id']}: {e}")
            return found, errors

        def _on_repos(result):
            found, errors = result
            self._add_repo_btn.set_sensitive(True)
            self._add_repo_btn.set_label("Add Repository...")
            if errors:
                self._show_error("Failed to load repositories:\n" + "\n".join(errors))
            if found:
                self._show_repo_picker(found)
            elif not errors:
                self._show_error("No repositories found.")

        run_in_background(_fetch, _on_repos)

    def _show_repo_picker(self, repos):
        dlg = Gtk.Dialog(
            title="Select Repository",
            transient_for=self,
            modal=True,
        )
        dlg.set_default_size(440, 440)

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
        for account, repo in repos:
            # Skip already-configured repos
            if config.find_repo(self._cfg, account["id"], repo.owner, repo.name):
                continue
            row = Gtk.ListBoxRow()
            lbl = Gtk.Label(xalign=0)
            lbl.set_markup(
                f"{GLib.markup_escape_text(repo.full_name)} "
                f'<span size="small" foreground="gray">'
                f"{GLib.markup_escape_text(account['id'])}</span>"
            )
            lbl.set_margin_start(8)
            lbl.set_margin_end(8)
            lbl.set_margin_top(4)
            lbl.set_margin_bottom(4)
            row.add(lbl)
            row._repo = (account, repo)
            listbox.add(row)
            rows.append((row, f"{repo.full_name} {account['id']}".lower()))

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
                account, repo = selected._repo
                config.add_repo(self._cfg, account["id"], repo.owner, repo.name)
                config.save(self._cfg)
                self._rebuild_repo_list()

        dlg.destroy()

    def _on_remove_repo(self, repo):
        config.remove_repo(self._cfg, repo["account"], repo["owner"], repo["name"])
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
        _show_error(self, msg)


class AccountDialog(Gtk.Dialog):
    """Add (or re-authenticate) a Gitea or MindBlown account.

    Gitea takes a personal access token; MindBlown takes email/password,
    which are exchanged for a long-lived token and not stored.
    """

    def __init__(self, parent, kind: str, account: dict = None):
        title = _KIND_TITLES[kind]
        super().__init__(
            title=f"Log In to {account['id']}" if account else f"Add {title} Account",
            transient_for=parent,
            modal=True,
        )
        self.set_default_size(420, -1)
        self.set_resizable(False)
        self._kind = kind
        self._verified = False

        box = self.get_content_area()
        box.set_spacing(8)
        box.set_margin_start(16)
        box.set_margin_end(16)
        box.set_margin_top(12)
        box.set_margin_bottom(12)

        box.add(Gtk.Label(label="Server URL:", xalign=0))
        self._url_entry = Gtk.Entry()
        self._url_entry.set_placeholder_text("https://git.example.com")
        if account:
            self._url_entry.set_text(account["base_url"])
            self._url_entry.set_sensitive(False)
        box.add(self._url_entry)

        if kind == "gitea":
            box.add(Gtk.Label(label="Access token:", xalign=0))
            self._token_entry = Gtk.Entry()
            self._token_entry.set_visibility(False)
            box.add(self._token_entry)

            hint = Gtk.Label(xalign=0)
            hint.set_line_wrap(True)
            hint.set_markup(
                '<span size="small">Scopes needed: read:user, read:repository, '
                "write:issue (and read:organization for org labels).</span>"
            )
            box.add(hint)

            token_btn = Gtk.Button(label="Create Token in Browser")
            token_btn.connect("clicked", self._on_open_token_page)
            box.add(token_btn)
        else:
            box.add(Gtk.Label(label="Email:", xalign=0))
            self._email_entry = Gtk.Entry()
            box.add(self._email_entry)
            box.add(Gtk.Label(label="Password:", xalign=0))
            self._password_entry = Gtk.Entry()
            self._password_entry.set_visibility(False)
            self._password_entry.set_activates_default(True)
            box.add(self._password_entry)

        self.add_button("Cancel", Gtk.ResponseType.CANCEL)
        self._ok_btn = self.add_button("Log In", Gtk.ResponseType.OK)
        self._ok_btn.get_style_context().add_class("suggested-action")
        self.set_default_response(Gtk.ResponseType.OK)

        self.connect("response", self._on_response)
        self.show_all()

    def _base_url(self) -> str:
        text = self._url_entry.get_text().strip()
        return config.normalize_base_url(text) if text else ""

    def _on_open_token_page(self, _btn):
        base_url = self._base_url()
        if not base_url:
            _show_error(self, "Please enter the server URL first.")
            return
        webbrowser.open(GiteaAPI(base_url).token_settings_url())

    def _on_response(self, dialog, response_id):
        # _on_verified closes the dialog with OK; let that one through
        if response_id != Gtk.ResponseType.OK or self._verified:
            return
        # Keep the dialog open until the credentials have been verified
        dialog.stop_emission_by_name("response")

        base_url = self._base_url()
        if not base_url:
            _show_error(self, "Server URL is required.")
            return

        if self._kind == "gitea":
            token = self._token_entry.get_text().strip()
            if not token:
                _show_error(self, "Access token is required.")
                return

            def _verify():
                return token, GiteaAPI(base_url).get_username(token)
        else:
            email = self._email_entry.get_text().strip()
            password = self._password_entry.get_text()
            if not email or not password:
                _show_error(self, "Email and password are required.")
                return

            def _verify():
                return MindBlownAPI(base_url).login(email, password)

        self._ok_btn.set_sensitive(False)
        self._ok_btn.set_label("Logging in...")

        def _run():
            try:
                GLib.idle_add(self._on_verified, base_url, *_verify())
            except Exception as e:
                GLib.idle_add(self._on_failed, str(e))

        threading.Thread(target=_run, daemon=True).start()

    def _on_verified(self, base_url, token, username):
        cfg = config.load()
        account = config.add_account(cfg, self._kind, base_url, username)
        config.save(cfg)
        store_token(account["id"], token)
        self._verified = True
        self.response(Gtk.ResponseType.OK)

    def _on_failed(self, msg):
        self._ok_btn.set_sensitive(True)
        self._ok_btn.set_label("Log In")
        _show_error(self, f"Login failed:\n{msg}")


class RepoConfigDialog(Gtk.Dialog):
    """Sub-dialog for configuring a repo's color and default labels."""

    def __init__(self, parent, app, repo):
        super().__init__(
            title=f"Configure {config.repo_display_name(parent._cfg, repo)}",
            transient_for=parent,
            modal=True,
        )
        self.set_default_size(400, -1)
        self.set_resizable(False)
        self._app = app
        self._repo = repo
        self._parent_cfg = parent._cfg

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
        client = self._app.client(self._repo["account"], self._parent_cfg)
        if not client:
            return
        api, token = client

        self._labels_spinner.show()
        self._labels_spinner.start()

        owner = self._repo["owner"]
        name = self._repo["name"]

        def _fetch():
            return api.list_labels(token, owner, name)

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
