"""Create Issue dialog with colored label chips."""

import threading

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, GLib, Gtk

from .. import config
from ..api import GitHubAPI, Label
from ..keyring import get_token
from ..queue import IssueQueue, QueuedIssue

import requests


def _label_css(color_hex: str) -> str:
    """Return CSS for a label toggle button given a hex color (no '#')."""
    r = int(color_hex[0:2], 16)
    g = int(color_hex[2:4], 16)
    b = int(color_hex[4:6], 16)
    # Perceived luminance
    lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255
    fg = "#000000" if lum > 0.5 else "#ffffff"
    return (
        f"button.label-chip {{"
        f"  background: #{color_hex};"
        f"  color: {fg};"
        f"  border: none;"
        f"  border-radius: 12px;"
        f"  padding: 2px 10px;"
        f"  min-height: 0;"
        f"}}"
        f"button.label-chip:checked {{"
        f"  background: #{color_hex};"
        f"  color: {fg};"
        f"  border: 2px solid {fg};"
        f"}}"
    )


class CreateIssueDialog(Gtk.Dialog):
    def __init__(self, app):
        super().__init__(
            title="Create Issue",
            transient_for=None,
            modal=True,
        )
        self.set_default_size(520, 480)
        self._app = app
        self._cfg = config.load()
        self._labels: list[Label] = []
        self._label_buttons: list[tuple[Gtk.ToggleButton, Label]] = []

        box = self.get_content_area()
        box.set_spacing(8)
        box.set_margin_start(16)
        box.set_margin_end(16)
        box.set_margin_top(12)
        box.set_margin_bottom(12)

        # Header: repo info + queue count
        repo = config.get_repo(self._cfg)
        header_parts = []
        if repo:
            header_parts.append(f"{repo[0]}/{repo[1]}")
        n = self._app.queue.count()
        if n > 0:
            header_parts.append(f"({n} pending)")
        if header_parts:
            header = Gtk.Label(xalign=0)
            header.set_markup(
                f'<span size="small" foreground="gray">{" — ".join(header_parts)}</span>'
            )
            box.add(header)

        # Title
        box.add(Gtk.Label(label="Title:", xalign=0))
        self._title_entry = Gtk.Entry()
        self._title_entry.set_placeholder_text("Issue title")
        box.add(self._title_entry)

        # Body
        box.add(Gtk.Label(label="Body:", xalign=0))
        scroll = Gtk.ScrolledWindow()
        scroll.set_vexpand(True)
        scroll.set_min_content_height(120)
        self._body_view = Gtk.TextView()
        self._body_view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        scroll.add(self._body_view)
        box.add(scroll)

        # Labels section
        self._labels_header = Gtk.Label(label="Labels:", xalign=0)
        box.add(self._labels_header)

        self._labels_flow = Gtk.FlowBox()
        self._labels_flow.set_selection_mode(Gtk.SelectionMode.NONE)
        self._labels_flow.set_max_children_per_line(10)
        self._labels_flow.set_row_spacing(4)
        self._labels_flow.set_column_spacing(4)
        box.add(self._labels_flow)

        self._labels_spinner = Gtk.Spinner()
        box.add(self._labels_spinner)

        # Buttons
        self.add_button("Cancel", Gtk.ResponseType.CANCEL)
        self._submit_btn = self.add_button("Submit", Gtk.ResponseType.OK)
        self._submit_btn.get_style_context().add_class("suggested-action")

        self.connect("response", self._on_response)
        self.show_all()
        self._labels_spinner.hide()

        # Fetch labels in background
        self._fetch_labels()

    def _fetch_labels(self):
        token = get_token()
        repo = config.get_repo(self._cfg)
        if not token or not repo:
            self._labels_header.hide()
            return

        self._labels_spinner.show()
        self._labels_spinner.start()

        owner, name = repo

        def _fetch():
            return self._app.api.list_labels(token, owner, name)

        def _on_labels(labels):
            self._labels_spinner.stop()
            self._labels_spinner.hide()
            self._labels = labels
            self._populate_labels()

        def _on_error(msg):
            self._labels_spinner.stop()
            self._labels_spinner.hide()
            self._labels_header.set_text(f"Labels: (failed to load)")

        def _run():
            try:
                labels = _fetch()
                GLib.idle_add(_on_labels, labels)
            except Exception as e:
                GLib.idle_add(_on_error, str(e))

        threading.Thread(target=_run, daemon=True).start()

    def _populate_labels(self):
        # Clear existing
        for child in self._labels_flow.get_children():
            self._labels_flow.remove(child)
        self._label_buttons.clear()

        if not self._labels:
            self._labels_header.hide()
            self._labels_flow.hide()
            return

        self._labels_header.show()
        self._labels_flow.show()

        for label in self._labels:
            btn = Gtk.ToggleButton(label=label.name)
            btn.get_style_context().add_class("label-chip")

            # Apply per-label color via CSS provider
            css = _label_css(label.color)
            provider = Gtk.CssProvider()
            provider.load_from_data(css.encode())
            btn.get_style_context().add_provider(
                provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
            )

            self._label_buttons.append((btn, label))
            self._labels_flow.add(btn)

        self._labels_flow.show_all()

    def _get_selected_labels(self) -> list[str]:
        return [
            label.name
            for btn, label in self._label_buttons
            if btn.get_active()
        ]

    def _on_response(self, dialog, response_id):
        if response_id != Gtk.ResponseType.OK:
            return

        title = self._title_entry.get_text().strip()
        if not title:
            self._show_error("Title is required.")
            dialog.stop_emission_by_name("response")
            return

        buf = self._body_view.get_buffer()
        body = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), True).strip()
        labels = self._get_selected_labels()

        token = get_token()
        repo = config.get_repo(self._cfg)
        if not token or not repo:
            self._show_error("Not logged in or no repository selected.")
            dialog.stop_emission_by_name("response")
            return

        owner, name = repo

        # Disable submit while working
        self._submit_btn.set_sensitive(False)
        self._submit_btn.set_label("Submitting...")

        # Stop the dialog from closing
        dialog.stop_emission_by_name("response")

        def _submit():
            try:
                result = self._app.api.create_issue(token, owner, name, title, body, labels)
                GLib.idle_add(self._on_submit_success, result)
            except requests.ConnectionError:
                issue = QueuedIssue(
                    title=title, body=body, labels=labels,
                    owner=owner, repo=name,
                )
                self._app.queue.enqueue(issue)
                n = self._app.queue.count()
                GLib.idle_add(self._on_submit_queued, n)
            except Exception as e:
                GLib.idle_add(self._on_submit_error, str(e))

        threading.Thread(target=_submit, daemon=True).start()

    def _on_submit_success(self, result):
        self._app._notify("Issue created", f"#{result.number}: {result.title}")
        self.response(Gtk.ResponseType.CLOSE)

    def _on_submit_queued(self, n):
        self._app._notify("Issue queued", f"Issue queued ({n} pending)")
        self.response(Gtk.ResponseType.CLOSE)

    def _on_submit_error(self, msg):
        self._submit_btn.set_sensitive(True)
        self._submit_btn.set_label("Submit")
        self._show_error(f"Failed to create issue:\n{msg}")

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
