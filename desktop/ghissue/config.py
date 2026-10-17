"""XDG-compliant configuration management."""

import json
import os
import tempfile
from urllib.parse import urlparse

_DEFAULT_CLIENT_ID = "Ov23liDuXSl6yUoPGfue"

_CONFIG_DIR = os.path.join(
    os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")),
    "ghissue",
)
_CONFIG_FILE = os.path.join(_CONFIG_DIR, "config.json")

GITHUB_ACCOUNT_ID = "github.com"

# Account kinds: "github" (device flow), "gitea" (personal access token),
# "mindblown" (email/password exchanged for a long-lived token).
ACCOUNT_KINDS = ("github", "gitea", "mindblown")

_GITHUB_ACCOUNT = {
    "id": GITHUB_ACCOUNT_ID,
    "kind": "github",
    "base_url": "https://github.com",
    "username": "",
}

_DEFAULTS = {
    "client_id": _DEFAULT_CLIENT_ID,
    "accounts": [],  # _migrate() seeds the GitHub account
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
    """Migrate old configs: single-repo → multi-repo → multi-account."""
    if "repos" not in data:
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

    # Pre-account configs only knew GitHub
    if "accounts" not in data:
        data["accounts"] = [dict(_GITHUB_ACCOUNT)]
    for r in data["repos"]:
        r.setdefault("account", GITHUB_ACCOUNT_ID)
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


def get_repo(cfg: dict = None) -> dict | None:
    """Return the first configured repo entry, or None."""
    repos = get_repos(cfg)
    return repos[0] if repos else None


def find_repo(cfg: dict, account: str | None, owner: str, name: str) -> dict | None:
    """Find a repo entry by account/owner/name.

    account=None matches any account (legacy two-argument DBus callers).
    """
    for r in cfg.get("repos", []):
        if (account is None or r["account"] == account) \
                and r["owner"] == owner and r["name"] == name:
            return r
    return None


def add_repo(cfg: dict, account: str, owner: str, name: str,
             color: str = None, default_labels: list[str] = None) -> dict:
    """Add a repo if not already present. Returns the repo entry."""
    existing = find_repo(cfg, account, owner, name)
    if existing:
        return existing
    repo = {
        "account": account,
        "owner": owner,
        "name": name,
        "color": color or PRESET_COLORS[3],
        "default_labels": default_labels or [],
    }
    cfg.setdefault("repos", []).append(repo)
    return repo


def remove_repo(cfg: dict, account: str, owner: str, name: str):
    """Remove a repo by account/owner/name."""
    cfg["repos"] = [
        r for r in cfg.get("repos", [])
        if not (r["account"] == account
                and r["owner"] == owner and r["name"] == name)
    ]


def find_map_repo(cfg: dict, account: str, map_id: str) -> dict:
    """Find a MindBlown repo entry by map id (stored as owner); {} if absent."""
    for r in cfg.get("repos", []):
        if r["account"] == account and r["owner"] == map_id:
            return r
    return {}


def repo_display_name(cfg: dict, repo: dict) -> str:
    """Human-readable repo name, suffixed with the host for non-GitHub accounts.

    MindBlown "repos" are maps: owner holds the map id, name the map name.
    """
    account = find_account(cfg, repo["account"])
    if account and account["kind"] == "mindblown":
        base = repo["name"]
    else:
        base = f"{repo['owner']}/{repo['name']}"
    if repo["account"] == GITHUB_ACCOUNT_ID:
        return base
    return f"{base} ({repo['account']})"


# ── Accounts ──

def account_id_for_url(base_url: str) -> str:
    """Accounts are keyed by host, e.g. 'git.project.li'."""
    return urlparse(base_url).netloc.lower()


def normalize_base_url(base_url: str) -> str:
    base_url = base_url.strip().rstrip("/")
    if "://" not in base_url:
        base_url = "https://" + base_url
    return base_url


def get_accounts(cfg: dict) -> list[dict]:
    return cfg.get("accounts", [])


def find_account(cfg: dict, account_id: str) -> dict | None:
    for a in cfg.get("accounts", []):
        if a["id"] == account_id:
            return a
    return None


def add_account(cfg: dict, kind: str, base_url: str, username: str = "") -> dict:
    """Add or update an account for *base_url*. Returns the account entry."""
    base_url = normalize_base_url(base_url)
    account_id = account_id_for_url(base_url)
    account = find_account(cfg, account_id)
    if account is None:
        account = {"id": account_id}
        cfg.setdefault("accounts", []).append(account)
    account.update(kind=kind, base_url=base_url, username=username)
    return account


def remove_account(cfg: dict, account_id: str):
    """Remove an account and all of its repos."""
    cfg["accounts"] = [a for a in cfg.get("accounts", []) if a["id"] != account_id]
    cfg["repos"] = [r for r in cfg.get("repos", []) if r["account"] != account_id]
