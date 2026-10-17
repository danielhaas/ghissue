"""Per-account token storage via libsecret with plaintext file fallback."""

import os

_SCHEMA_NAME = "com.github.ghissue.account-token"
_LEGACY_SCHEMA_NAME = "com.github.ghissue.token"
_GITHUB_ACCOUNT_ID = "github.com"

_CONFIG_DIR = os.path.join(
    os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")),
    "ghissue",
)
_FALLBACK_DIR = os.path.join(_CONFIG_DIR, "tokens")
_LEGACY_FALLBACK_FILE = os.path.join(_CONFIG_DIR, "token")

_Secret = None
_schema = None
_legacy_schema = None


def _init_libsecret():
    global _Secret, _schema, _legacy_schema
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
            {"account": _Secret.SchemaAttributeType.STRING},
        )
        _legacy_schema = _Secret.Schema.new(
            _LEGACY_SCHEMA_NAME,
            _Secret.SchemaFlags.NONE,
            {"app": _Secret.SchemaAttributeType.STRING},
        )
        return True
    except (ImportError, ValueError):
        return False


def store_token(account_id: str, token: str):
    """Store the token for an account."""
    if _init_libsecret():
        try:
            _Secret.password_store_sync(
                _schema, {"account": account_id}, _Secret.COLLECTION_DEFAULT,
                f"ghissue token ({account_id})", token, None,
            )
            return
        except Exception:
            pass
    _store_fallback(account_id, token)


def get_token(account_id: str) -> str | None:
    """Retrieve the stored token for an account, or None."""
    if _init_libsecret():
        try:
            token = _Secret.password_lookup_sync(
                _schema, {"account": account_id}, None,
            )
            if token:
                return token
        except Exception:
            pass
    token = _get_fallback(account_id)
    if token is None and account_id == _GITHUB_ACCOUNT_ID:
        token = _migrate_legacy_github_token()
    return token


def clear_token(account_id: str):
    """Remove the stored token for an account."""
    if _init_libsecret():
        try:
            _Secret.password_clear_sync(_schema, {"account": account_id}, None)
        except Exception:
            pass
    try:
        os.unlink(_fallback_file(account_id))
    except FileNotFoundError:
        pass


def is_logged_in(account_id: str) -> bool:
    return get_token(account_id) is not None


# ── Migration from the single-token (GitHub-only) layout ──

def _migrate_legacy_github_token() -> str | None:
    token = None
    if _init_libsecret():
        try:
            token = _Secret.password_lookup_sync(
                _legacy_schema, {"app": "ghissue"}, None,
            )
        except Exception:
            token = None
    if not token:
        try:
            with open(_LEGACY_FALLBACK_FILE, "r") as f:
                token = f.read().strip() or None
        except FileNotFoundError:
            pass
    if not token:
        return None

    store_token(_GITHUB_ACCOUNT_ID, token)
    if _init_libsecret():
        try:
            _Secret.password_clear_sync(_legacy_schema, {"app": "ghissue"}, None)
        except Exception:
            pass
    try:
        os.unlink(_LEGACY_FALLBACK_FILE)
    except FileNotFoundError:
        pass
    return token


# ── Fallback: plaintext file per account with 0600 perms ──

def _fallback_file(account_id: str) -> str:
    return os.path.join(_FALLBACK_DIR, account_id.replace("/", "_"))


def _store_fallback(account_id: str, token: str):
    os.makedirs(_FALLBACK_DIR, mode=0o700, exist_ok=True)
    fd = os.open(_fallback_file(account_id),
                 os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(token)


def _get_fallback(account_id: str) -> str | None:
    try:
        with open(_fallback_file(account_id), "r") as f:
            token = f.read().strip()
        return token or None
    except FileNotFoundError:
        return None
