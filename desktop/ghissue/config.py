"""XDG-compliant configuration management."""

import json
import os
import tempfile

_DEFAULT_CLIENT_ID = "Ov23liDuXSl6yUoPGfue"

_CONFIG_DIR = os.path.join(
    os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")),
    "ghissue",
)
_CONFIG_FILE = os.path.join(_CONFIG_DIR, "config.json")

_DEFAULTS = {
    "client_id": _DEFAULT_CLIENT_ID,
    "repo_owner": "",
    "repo_name": "",
}


def _ensure_dir():
    os.makedirs(_CONFIG_DIR, exist_ok=True)


def load() -> dict:
    """Load config from disk, returning defaults for missing keys."""
    try:
        with open(_CONFIG_FILE, "r") as f:
            data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        data = {}
    return {**_DEFAULTS, **data}


def save(cfg: dict):
    """Atomically write config to disk."""
    _ensure_dir()
    fd, tmp = tempfile.mkstemp(dir=_CONFIG_DIR, suffix=".tmp")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(cfg, f, indent=2)
        os.replace(tmp, _CONFIG_FILE)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def get_repo(cfg: dict = None) -> tuple[str, str] | None:
    """Return (owner, name) if both are set, else None."""
    cfg = cfg or load()
    owner, name = cfg.get("repo_owner", ""), cfg.get("repo_name", "")
    if owner and name:
        return owner, name
    return None
