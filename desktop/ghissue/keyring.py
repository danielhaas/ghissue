"""Token storage via libsecret with plaintext file fallback."""

import os
import stat

_SCHEMA_NAME = "com.github.ghissue.token"
_FALLBACK_DIR = os.path.join(
    os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")),
    "ghissue",
)
_FALLBACK_FILE = os.path.join(_FALLBACK_DIR, "token")

_Secret = None
_schema = None


def _init_libsecret():
    global _Secret, _schema
    if _Secret is not None:
        return True
    try:
        import gi
        gi.require_version("Secret", "1")
        from gi.repository import Secret as SecretLib
        _Secret = SecretLib
        _schema = _Secret.Schema.new(
            _SCHEMA_NAME,
            _Secret.SchemaFlags.NONE,
            {"app": _Secret.SchemaAttributeType.STRING},
        )
        return True
    except (ImportError, ValueError):
        return False


def _attrs():
    return {"app": "ghissue"}


def store_token(token: str):
    """Store an OAuth token."""
    if _init_libsecret():
        try:
            _Secret.password_store_sync(
                _schema, _attrs(), _Secret.COLLECTION_DEFAULT,
                "ghissue GitHub token", token, None,
            )
            return
        except Exception:
            pass
    _store_fallback(token)


def get_token() -> str | None:
    """Retrieve the stored token, or None."""
    if _init_libsecret():
        try:
            token = _Secret.password_lookup_sync(_schema, _attrs(), None)
            if token:
                return token
        except Exception:
            pass
    return _get_fallback()


def clear_token():
    """Remove the stored token."""
    if _init_libsecret():
        try:
            _Secret.password_clear_sync(_schema, _attrs(), None)
        except Exception:
            pass
    try:
        os.unlink(_FALLBACK_FILE)
    except FileNotFoundError:
        pass


def is_logged_in() -> bool:
    return get_token() is not None


# ── Fallback: plaintext file with 0600 perms ──

def _store_fallback(token: str):
    os.makedirs(_FALLBACK_DIR, exist_ok=True)
    fd = os.open(_FALLBACK_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(token)


def _get_fallback() -> str | None:
    try:
        with open(_FALLBACK_FILE, "r") as f:
            token = f.read().strip()
        return token or None
    except FileNotFoundError:
        return None
