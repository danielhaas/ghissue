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
    "repos": [],
}

# Preset colors matching Android widget palette
PRESET_COLORS = [
    "#7B1FA2",  # purple
    "#1565C0",  # blue
    "#00796B",  # teal
    "#238636",  # green
    "#EF6C00",  # orange
    "#C62828",  # red
    "#C2185B",  # pink
    "#283593",  # indigo
]


def _ensure_dir():
    os.makedirs(_CONFIG_DIR, exist_ok=True)


def _migrate(data: dict) -> dict:
    """Migrate old single-repo config to multi-repo format."""
    if "repos" in data:
        return data

    owner = data.pop("repo_owner", "")
    name = data.pop("repo_name", "")
    data["repos"] = []
    if owner and name:
        data["repos"].append({
            "owner": owner,
            "name": name,
            "color": PRESET_COLORS[3],  # green
            "default_labels": [],
        })
    return data


def load() -> dict:
    """Load config from disk, returning defaults for missing keys."""
    try:
        with open(_CONFIG_FILE, "r") as f:
            data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        data = {}
    data = _migrate(data)
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


def get_repos(cfg: dict = None) -> list[dict]:
    """Return the list of configured repos."""
    cfg = cfg or load()
    return cfg.get("repos", [])


def get_repo(cfg: dict = None) -> tuple[str, str] | None:
    """Return (owner, name) of the first repo, or None. Backwards compat."""
    repos = get_repos(cfg)
    if repos:
        r = repos[0]
        return r["owner"], r["name"]
    return None


def find_repo(cfg: dict, owner: str, name: str) -> dict | None:
    """Find a repo entry by owner/name."""
    for r in cfg.get("repos", []):
        if r["owner"] == owner and r["name"] == name:
            return r
    return None


def add_repo(cfg: dict, owner: str, name: str,
             color: str = None, default_labels: list[str] = None) -> dict:
    """Add a repo if not already present. Returns the repo entry."""
    existing = find_repo(cfg, owner, name)
    if existing:
        return existing
    repo = {
        "owner": owner,
        "name": name,
        "color": color or PRESET_COLORS[3],
        "default_labels": default_labels or [],
    }
    cfg.setdefault("repos", []).append(repo)
    return repo


def remove_repo(cfg: dict, owner: str, name: str):
    """Remove a repo by owner/name."""
    cfg["repos"] = [
        r for r in cfg.get("repos", [])
        if not (r["owner"] == owner and r["name"] == name)
    ]
