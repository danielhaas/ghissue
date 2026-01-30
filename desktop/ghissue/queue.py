"""Offline issue queue with JSON file persistence."""

import json
import os
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass, field, asdict

import requests

_DATA_DIR = os.path.join(
    os.environ.get("XDG_DATA_HOME", os.path.expanduser("~/.local/share")),
    "ghissue",
)
_QUEUE_FILE = os.path.join(_DATA_DIR, "queue.json")


@dataclass
class QueuedIssue:
    title: str
    body: str
    labels: list[str]
    owner: str
    repo: str
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    timestamp: float = field(default_factory=time.time)


@dataclass
class DrainResult:
    submitted: int = 0
    failed: int = 0
    stopped_reason: str | None = None  # "network", "auth"


class IssueQueue:
    def __init__(self):
        self._lock = threading.Lock()

    def _load(self) -> list[dict]:
        try:
            with open(_QUEUE_FILE, "r") as f:
                return json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            return []

    def _save(self, items: list[dict]):
        os.makedirs(_DATA_DIR, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=_DATA_DIR, suffix=".tmp")
        try:
            with os.fdopen(fd, "w") as f:
                json.dump(items, f, indent=2)
            os.replace(tmp, _QUEUE_FILE)
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise

    def enqueue(self, issue: QueuedIssue):
        with self._lock:
            items = self._load()
            items.append(asdict(issue))
            self._save(items)

    def remove(self, issue_id: str):
        with self._lock:
            items = self._load()
            items = [i for i in items if i.get("id") != issue_id]
            self._save(items)

    def get_all(self) -> list[QueuedIssue]:
        with self._lock:
            items = self._load()
        return [
            QueuedIssue(
                id=i["id"],
                title=i["title"],
                body=i["body"],
                labels=i.get("labels", []),
                owner=i["owner"],
                repo=i["repo"],
                timestamp=i.get("timestamp", 0),
            )
            for i in items
        ]

    def count(self) -> int:
        with self._lock:
            return len(self._load())

    def drain(self, api, token: str) -> DrainResult:
        """Submit all queued issues. Returns drain result.

        - ConnectionError → stop (network down)
        - 401 → stop (auth invalid)
        - Other HTTP error → skip item, continue
        """
        result = DrainResult()
        items = self.get_all()
        if not items:
            return result

        for issue in items:
            try:
                api.create_issue(
                    token=token,
                    owner=issue.owner,
                    repo=issue.repo,
                    title=issue.title,
                    body=issue.body,
                    labels=issue.labels,
                )
                self.remove(issue.id)
                result.submitted += 1
            except requests.ConnectionError:
                result.stopped_reason = "network"
                break
            except requests.HTTPError as e:
                if e.response is not None and e.response.status_code == 401:
                    result.stopped_reason = "auth"
                    break
                result.failed += 1
                self.remove(issue.id)

        return result
