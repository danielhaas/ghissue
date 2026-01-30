"""Connectivity monitoring via Gio.NetworkMonitor."""

import gi

gi.require_version("Gio", "2.0")
from gi.repository import Gio


class NetworkMonitor:
    def __init__(self, on_network_available=None):
        """
        Args:
            on_network_available: Callback invoked on the main thread when
                network connectivity is restored.
        """
        self._callback = on_network_available
        self._monitor = Gio.NetworkMonitor.get_default()
        self._monitor.connect("network-changed", self._on_changed)

    def _on_changed(self, monitor, network_available):
        if network_available and self._callback:
            self._callback()

    def is_available(self) -> bool:
        return self._monitor.get_network_available()
