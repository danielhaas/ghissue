"""Create Issue dialog with colored label chips."""

import threading

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, GLib, Gtk

from .. import config
from ..api import Label
from ..queue import IssueQueue, QueuedIssue

import requests


_RESPONSE_REFINE = 1

_VERDICT_TEXT = {
    "covered": "An existing ticket already covers this:",
    "extends": "This adds to an existing ticket:",
    "regression": "This looks like a regression of:",
}


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
    def __init__(self, app, repo_cfg: dict):
        super().__init__(
            title="Create Issue",
            transient_for=None,
            modal=True,
        )
        self.set_default_size(520, 480)
        self._app = app
        self._account = repo_cfg["account"]
        self._owner = repo_cfg["owner"]
        self._repo = repo_cfg["name"]
        self._repo_cfg = repo_cfg
        account = config.find_account(app.cfg, self._account)
        self._is_mindblown = bool(account) and account["kind"] == "mindblown"
        # Ticket intake state (MindBlown only): session, latest draft, its result
        self._intake_id = None
        self._draft = None
        self._intake = None
        self._labels: list[Label] = []
        self._label_buttons: list[tuple[Gtk.ToggleButton, Label]] = []

        self._default_labels = set(repo_cfg.get("default_labels", []))

        box = self.get_content_area()
        box.set_spacing(8)
        box.set_margin_start(16)
        box.set_margin_end(16)
        box.set_margin_top(12)
        box.set_margin_bottom(12)

        # Header: repo info + queue count
        header_parts = [config.repo_display_name(app.cfg, repo_cfg)]
        n = self._app.queue.count()
        if n > 0:
            header_parts.append(f"({n} pending)")
        header = Gtk.Label(xalign=0)
        header.set_markup(
            f'<span size="small" foreground="gray">{" — ".join(header_parts)}</span>'
        )
        box.add(header)

        if self._is_mindblown:
            self._build_refine_panel(box)

        # Title
        box.add(Gtk.Label(label="Title:", xalign=0))
        self._title_entry = Gtk.Entry()
        self._title_entry.set_placeholder_text("Issue title")
        self._title_entry.connect("key-press-event", self._on_key_press)
        box.add(self._title_entry)

        # Body
        box.add(Gtk.Label(label="Body:", xalign=0))
        scroll = Gtk.ScrolledWindow()
        scroll.set_vexpand(True)
        scroll.set_min_content_height(120)
        self._body_view = Gtk.TextView()
        self._body_view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        self._body_view.connect("key-press-event", self._on_key_press)
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
        # Repos can have dozens of labels; scroll them rather than growing
        # the dialog past the bottom of the screen.
        self._labels_scroll = Gtk.ScrolledWindow()
        self._labels_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self._labels_scroll.set_propagate_natural_height(True)
        self._labels_scroll.set_max_content_height(140)
        self._labels_scroll.add(self._labels_flow)
        box.add(self._labels_scroll)

        self._labels_spinner = Gtk.Spinner()
        box.add(self._labels_spinner)

        # Buttons
        self.add_button("Cancel", Gtk.ResponseType.CANCEL)
        self._refine_btn = None
        if self._is_mindblown:
            self._refine_btn = self.add_button("Refine…", _RESPONSE_REFINE)
            self._refine_btn.set_tooltip_text(
                "Let MindBlown rewrite the ticket and look for similar ones"
            )
        self._submit_btn = self.add_button("Submit", Gtk.ResponseType.OK)
        self._submit_btn.get_style_context().add_class("suggested-action")

        self.connect("response", self._on_response)
        self.show_all()
        self._labels_spinner.hide()

        # Fetch labels in background
        self._fetch_labels()

    # ── Refine (MindBlown ticket intake) ──

    def _build_refine_panel(self, box):
        self._refine_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self._refine_box.set_no_show_all(True)

        status_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self._refine_spinner = Gtk.Spinner()
        status_row.pack_start(self._refine_spinner, False, False, 0)
        self._refine_status = Gtk.Label(xalign=0)
        self._refine_status.set_line_wrap(True)
        self._refine_status.set_selectable(True)
        status_row.pack_start(self._refine_status, True, True, 0)
        self._refine_box.add(status_row)

        self._questions_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        self._refine_box.add(self._questions_box)

        self._existing_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        existing_scroll = Gtk.ScrolledWindow()
        existing_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        existing_scroll.set_propagate_natural_height(True)
        existing_scroll.set_max_content_height(180)
        existing_scroll.add(self._existing_box)
        self._refine_box.add(existing_scroll)

        self._refine_box.add(Gtk.Separator())
        box.add(self._refine_box)

    def _show_refine_panel(self):
        self._refine_box.set_no_show_all(False)
        self._refine_box.show_all()

    def _set_busy(self, busy: bool, status: str = None):
        self._submit_btn.set_sensitive(not busy)
        if self._is_mindblown:
            self._refine_btn.set_sensitive(not busy)
            for b in (self._questions_box, self._existing_box):
                b.set_sensitive(not busy)
            if busy:
                self._refine_spinner.start()
            else:
                self._refine_spinner.stop()
            if status is not None:
                self._refine_status.set_text(status)

    def _get_body(self) -> str:
        buf = self._body_view.get_buffer()
        return buf.get_text(buf.get_start_iter(), buf.get_end_iter(), True).strip()

    def _start_refine(self, message: str = None):
        if message is None:
            title = self._title_entry.get_text().strip()
            body = self._get_body()
            message = f"{title}\n\n{body}".strip()
        if not message:
            self._show_error("Write something to refine first.")
            return
        client = self._app.client(self._account)
        if not client:
            self._show_error(f"Not logged in to {self._account}.")
            return
        api, token = client

        self._show_refine_panel()
        self._set_busy(True, "Asking MindBlown…")
        intake_id = self._intake_id

        def _run():
            try:
                result = api.intake(token, self._owner, message, intake_id)
                GLib.idle_add(self._on_intake, result)
            except Exception as e:
                GLib.idle_add(self._on_intake_error, str(e))

        threading.Thread(target=_run, daemon=True).start()

    def _on_intake_error(self, msg):
        # An expired session is the usual cause; the next Refine starts fresh
        self._intake_id = None
        self._set_busy(False, f"Refine failed: {msg}")

    def _on_intake(self, result):
        self._intake = result
        self._intake_id = result.get("intakeId")
        status = result.get("text") or ""
        if result.get("stepLimit"):
            status += "\n(MindBlown ran out of steps; try Refine again.)"

        for child in self._questions_box.get_children():
            child.destroy()
        for q in result.get("questions", []):
            self._questions_box.add(self._make_question(q))

        draft = result.get("draft")
        if draft:
            self._draft = draft
            self._title_entry.set_text(draft["title"])
            self._body_view.get_buffer().set_text(draft["description"])
            draft_tags = set(draft.get("tags", []))
            for btn, label in self._label_buttons:
                if label.name in draft_tags:
                    btn.set_active(True)
        self._show_existing(draft)

        if not status:
            status = "Refined. Edit if needed, then Submit." if draft else ""
        self._set_busy(False, status)
        self._refine_box.show_all()

    def _make_question(self, q: dict) -> Gtk.Widget:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        lbl = Gtk.Label(xalign=0)
        lbl.set_line_wrap(True)
        lbl.set_markup(f"<b>{GLib.markup_escape_text(q['question'])}</b>")
        box.add(lbl)
        options = Gtk.FlowBox()
        options.set_selection_mode(Gtk.SelectionMode.NONE)
        options.set_max_children_per_line(4)
        for opt in q.get("options", []):
            btn = Gtk.Button(label=opt)
            btn.connect(
                "clicked",
                lambda _b, o=opt: self._start_refine(f"{q['question']} → {o}"),
            )
            options.add(btn)
        box.add(options)
        return box

    def _show_existing(self, draft):
        for child in self._existing_box.get_children():
            child.destroy()
        verdict = draft.get("verdict", "new") if draft else "new"
        existing = draft.get("existing", []) if draft else []
        if verdict == "new" or not existing:
            self._submit_btn.set_label("Submit")
            return

        head = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        head_lbl = Gtk.Label(label=_VERDICT_TEXT.get(verdict, "Similar tickets:"), xalign=0)
        head.pack_start(head_lbl, True, True, 0)
        nothing_btn = Gtk.Button(label="Nothing to Do")
        nothing_btn.connect("clicked", lambda _b: self._on_existing_action("none", None))
        if any(e["recommendation"] == "nothing" for e in existing):
            nothing_btn.get_style_context().add_class("suggested-action")
        head.pack_start(nothing_btn, False, False, 0)
        self._existing_box.add(head)

        for ex in existing:
            self._existing_box.add(self._make_existing_row(ex))
        self._submit_btn.set_label("Create Anyway")

    def _make_existing_row(self, ex: dict) -> Gtk.Widget:
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        ref = f"#{ex['issueNumber']} " if ex.get("issueNumber") else ""
        title = GLib.markup_escape_text(f"{ref}{ex['text']}")
        if ex.get("url"):
            title = f'<a href="{GLib.markup_escape_text(ex["url"])}">{title}</a>'
        facts = [f for f in (ex.get("status"), "fixed by PR" if ex.get("fixedByPr") else None) if f]
        lbl = Gtk.Label(xalign=0)
        lbl.set_line_wrap(True)
        lbl.set_markup(
            f"{title}"
            + (f' <span size="small" foreground="gray">{GLib.markup_escape_text(", ".join(facts))}</span>' if facts else "")
            + f'\n<span size="small">{GLib.markup_escape_text(ex.get("reason", ""))}</span>'
        )
        row.pack_start(lbl, True, True, 0)

        for action, text in (("comment", "Comment"), ("reopen", "Reopen")):
            btn = Gtk.Button(label=text)
            btn.set_valign(Gtk.Align.CENTER)
            if ex.get("recommendation") == action:
                btn.get_style_context().add_class("suggested-action")
            btn.connect("clicked", lambda _b, a=action: self._on_existing_action(a, ex))
            row.pack_start(btn, False, False, 0)
        return row

    def _on_existing_action(self, action: str, ex: dict | None):
        note = self._get_body() or self._title_entry.get_text().strip()
        client = self._app.client(self._account)
        if not client:
            self._show_error(f"Not logged in to {self._account}.")
            return
        api, token = client
        existing = {"nodeId": ex.get("nodeId"), "issueNumber": ex.get("issueNumber")} if ex else None
        self._set_busy(True, "Working…")
        intake_id = self._intake_id

        def _run():
            try:
                api.intake_existing_action(token, self._owner, action, existing, note, intake_id)
                GLib.idle_add(self._on_existing_done, action, ex)
            except Exception as e:
                GLib.idle_add(self._on_existing_error, str(e))

        threading.Thread(target=_run, daemon=True).start()

    def _on_existing_done(self, action, ex):
        if action == "none":
            self._app._notify("Nothing created", "An existing ticket already covers this.")
        else:
            ref = f"#{ex['issueNumber']}" if ex.get("issueNumber") else ex["text"]
            verb = "Commented on" if action == "comment" else "Reopened"
            self._app._notify(f"{verb} {ref}", ex["text"])
        self.response(Gtk.ResponseType.CLOSE)

    def _on_existing_error(self, msg):
        self._set_busy(False, "")
        self._show_error(f"Failed:\n{msg}")

    def _fetch_labels(self):
        client = self._app.client(self._account)
        if not client:
            self._labels_header.hide()
            return
        api, token = client

        self._labels_spinner.show()
        self._labels_spinner.start()

        def _fetch():
            return api.list_labels(token, self._owner, self._repo)

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

            # Pre-select default labels
            if label.name in self._default_labels:
                btn.set_active(True)

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

    def _on_key_press(self, widget, event):
        if (event.keyval in (Gdk.KEY_Return, Gdk.KEY_KP_Enter)
                and event.state & Gdk.ModifierType.CONTROL_MASK):
            self.response(Gtk.ResponseType.OK)
            return True
        return False

    def _get_selected_labels(self) -> list[str]:
        return [
            label.name
            for btn, label in self._label_buttons
            if btn.get_active()
        ]

    def _on_response(self, dialog, response_id):
        if response_id == _RESPONSE_REFINE:
            dialog.stop_emission_by_name("response")
            self._start_refine()
            return
        if response_id != Gtk.ResponseType.OK:
            return
        if not self._submit_btn.get_sensitive():
            # Ctrl+Enter while a request is running
            dialog.stop_emission_by_name("response")
            return

        title = self._title_entry.get_text().strip()
        if not title:
            self._show_error("Title is required.")
            dialog.stop_emission_by_name("response")
            return

        body = self._get_body()
        labels = self._get_selected_labels()

        client = self._app.client(self._account)
        if not client:
            self._show_error(f"Not logged in to {self._account}.")
            dialog.stop_emission_by_name("response")
            return
        api, token = client

        owner = self._owner
        name = self._repo

        # Disable submit while working
        self._set_busy(True)
        self._submit_btn.set_label("Submitting...")

        # Stop the dialog from closing
        dialog.stop_emission_by_name("response")

        intake = self._intake
        draft = self._draft
        if draft:
            # Submit what the user sees; keep the draft's hidden placement
            known = {label.name for label in self._labels}
            draft = dict(
                draft, title=title, description=body,
                tags=labels + [t for t in draft.get("tags", []) if t not in known],
            )
            top = draft.get("existing") or [None]
            existing = (
                {"nodeId": top[0].get("nodeId"), "issueNumber": top[0].get("issueNumber")}
                if top[0] and draft.get("verdict", "new") != "new" else None
            )
            create_issue = (
                self._repo_cfg.get("link_issue", True) and intake.get("repoConnected", False)
            )

        def _submit():
            try:
                if draft:
                    result = api.intake_create(
                        token, owner, draft, self._intake_id, create_issue, existing,
                    )
                else:
                    result = api.create_issue(token, owner, name, title, body, labels)
                GLib.idle_add(self._on_submit_success, result)
            except requests.ConnectionError:
                issue = QueuedIssue(
                    title=title, body=body, labels=labels,
                    owner=owner, repo=name, account=self._account,
                )
                self._app.queue.enqueue(issue)
                n = self._app.queue.count()
                GLib.idle_add(self._on_submit_queued, n)
            except Exception as e:
                GLib.idle_add(self._on_submit_error, str(e))

        threading.Thread(target=_submit, daemon=True).start()

    def _on_submit_success(self, result):
        summary = result.summary
        if result.warning:
            summary += f"\n{result.warning}"
        self._app._notify(
            "Issue created" if result.number else "Node created", summary,
        )
        self.response(Gtk.ResponseType.CLOSE)

    def _on_submit_queued(self, n):
        self._app._notify("Issue queued", f"Issue queued ({n} pending)")
        self.response(Gtk.ResponseType.CLOSE)

    def _on_submit_error(self, msg):
        self._set_busy(False)
        self._submit_btn.set_label("Create Anyway" if self._existing_box_has_rows() else "Submit")
        self._show_error(f"Failed to create issue:\n{msg}")

    def _existing_box_has_rows(self) -> bool:
        return self._is_mindblown and bool(self._existing_box.get_children())

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
