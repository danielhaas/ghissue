"""GitHub Device Flow OAuth helpers."""

import threading
import time

from . import api as _api


def request_code(gh_api: _api.GitHubAPI, client_id: str) -> _api.DeviceCodeResponse:
    """Request a device code from GitHub. Runs synchronously (call from bg thread)."""
    return gh_api.request_device_code(client_id)


def start_polling(
    gh_api: _api.GitHubAPI,
    client_id: str,
    device_code: str,
    interval: int,
    on_success,   # callable(token: str) — called from bg thread
    on_error,     # callable(msg: str)  — called from bg thread
):
    """Poll for the OAuth token in a background thread.

    on_success(token) is called once the user authorizes.
    on_error(message) is called on expiry or denial.
    """

    def _poll():
        poll_interval = interval
        while True:
            time.sleep(poll_interval)
            try:
                resp = gh_api.poll_for_token(client_id, device_code)
                on_success(resp.access_token)
                return
            except _api.OAuthPendingError:
                continue
            except _api.OAuthSlowDownError:
                poll_interval += 5
            except _api.OAuthExpiredError:
                on_error("Device code expired. Please try again.")
                return
            except _api.OAuthDeniedError:
                on_error("Access denied by user.")
                return
            except Exception as e:
                on_error(str(e))
                return

    t = threading.Thread(target=_poll, daemon=True)
    t.start()
    return t
