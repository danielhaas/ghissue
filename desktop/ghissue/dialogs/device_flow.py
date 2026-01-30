"""Device Flow OAuth dialog."""

import webbrowser

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")

from gi.repository import Gdk, GLib, Gtk

from .. import auth
from ..api import DeviceCodeResponse


class DeviceFlowDialog(Gtk.Dialog):
    """Shows the user code and polls for authorization."""

    def __init__(self, app, device_code_resp: DeviceCodeResponse):
        super().__init__(
            title="GitHub Login",
            transient_for=None,
            modal=True,
            destroy_with_parent=True,
        )
        self.set_default_size(400, -1)
        self.set_resizable(False)

        self._app = app
        self._resp = device_code_resp
        self._token = None

        box = self.get_content_area()
        box.set_spacing(12)
        box.set_margin_start(20)
        box.set_margin_end(20)
        box.set_margin_top(16)
        box.set_margin_bottom(16)

        # Instructions
        label = Gtk.Label(label="Enter this code on GitHub:")
        label.set_halign(Gtk.Align.CENTER)
        box.add(label)

        # User code — large, selectable
        code_label = Gtk.Label()
        code_label.set_markup(
            f'<span size="xx-large" weight="bold">{self._resp.user_code}</span>'
        )
        code_label.set_selectable(True)
        code_label.set_halign(Gtk.Align.CENTER)
        box.add(code_label)

        # Button row
        btn_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        btn_box.set_halign(Gtk.Align.CENTER)

        copy_btn = Gtk.Button(label="Copy Code")
        copy_btn.connect("clicked", self._on_copy)
        btn_box.add(copy_btn)

        open_btn = Gtk.Button(label="Open Browser")
        open_btn.connect("clicked", self._on_open_browser)
        btn_box.add(open_btn)

        box.add(btn_box)

        # Spinner
        self._spinner = Gtk.Spinner()
        self._spinner.start()
        box.add(self._spinner)

        self._status_label = Gtk.Label(label="Waiting for authorization...")
        self._status_label.set_halign(Gtk.Align.CENTER)
        box.add(self._status_label)

        # Cancel button
        self.add_button("Cancel", Gtk.ResponseType.CANCEL)

        self.show_all()

        # Start polling
        auth.start_polling(
            gh_api=self._app.api,
            client_id=self._app.cfg.get("client_id", ""),
            device_code=self._resp.device_code,
            interval=self._resp.interval,
            on_success=self._on_auth_success,
            on_error=self._on_auth_error,
        )

    @property
    def token(self) -> str | None:
        return self._token

    def _on_copy(self, _btn):
        clipboard = Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD)
        clipboard.set_text(self._resp.user_code, -1)
        clipboard.store()

    def _on_open_browser(self, _btn):
        webbrowser.open(self._resp.verification_uri)

    def _on_auth_success(self, token: str):
        self._token = token
        GLib.idle_add(self._finish_success)

    def _on_auth_error(self, msg: str):
        GLib.idle_add(self._finish_error, msg)

    def _finish_success(self):
        self._spinner.stop()
        self._status_label.set_text("Login successful!")
        self.response(Gtk.ResponseType.OK)

    def _finish_error(self, msg):
        self._spinner.stop()
        self._status_label.set_text(msg)
