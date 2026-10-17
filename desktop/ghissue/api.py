"""Forge API clients (GitHub, Gitea, MindBlown) using requests.

All three expose the same surface: list_repos, list_labels, create_issue.
"""

from dataclasses import dataclass, field
import requests

_API_BASE = "https://api.github.com"
_DEVICE_CODE_URL = "https://github.com/login/device/code"
_OAUTH_TOKEN_URL = "https://github.com/login/oauth/access_token"


@dataclass
class DeviceCodeResponse:
    device_code: str
    user_code: str
    verification_uri: str
    expires_in: int
    interval: int


@dataclass
class OAuthTokenResponse:
    access_token: str
    token_type: str
    scope: str


@dataclass
class Label:
    name: str
    color: str  # hex without '#'
    description: str = ""


@dataclass
class Repo:
    owner: str
    name: str
    full_name: str


@dataclass
class IssueResponse:
    number: int | None  # None for MindBlown nodes without a linked issue
    html_url: str
    title: str
    warning: str | None = None  # created, but something secondary failed

    @property
    def summary(self) -> str:
        return f"#{self.number}: {self.title}" if self.number else self.title


class OAuthPendingError(Exception):
    """Authorization is still pending."""


class OAuthSlowDownError(Exception):
    """Polling too fast, back off."""


class OAuthExpiredError(Exception):
    """Device code has expired."""


class OAuthDeniedError(Exception):
    """User denied access."""


_TIMEOUT = 20  # seconds; requests has no default and would wait forever


class _TimeoutSession(requests.Session):
    def request(self, *args, **kwargs):
        kwargs.setdefault("timeout", _TIMEOUT)
        return super().request(*args, **kwargs)


def _new_session() -> requests.Session:
    session = _TimeoutSession()
    session.headers.update({
        "Accept": "application/json",
        "User-Agent": "ghissue-desktop/1.0",
    })
    return session


class GitHubAPI:
    def __init__(self):
        self.session = _new_session()

    def _auth_headers(self, token: str) -> dict:
        return {"Authorization": f"token {token}"}

    # ── OAuth Device Flow ──

    def request_device_code(self, client_id: str) -> DeviceCodeResponse:
        resp = self.session.post(_DEVICE_CODE_URL, json={
            "client_id": client_id,
            "scope": "repo",
        })
        resp.raise_for_status()
        d = resp.json()
        return DeviceCodeResponse(
            device_code=d["device_code"],
            user_code=d["user_code"],
            verification_uri=d["verification_uri"],
            expires_in=d["expires_in"],
            interval=d["interval"],
        )

    def poll_for_token(self, client_id: str, device_code: str) -> OAuthTokenResponse:
        resp = self.session.post(_OAUTH_TOKEN_URL, json={
            "client_id": client_id,
            "device_code": device_code,
            "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
        })
        resp.raise_for_status()
        d = resp.json()

        error = d.get("error")
        if error == "authorization_pending":
            raise OAuthPendingError()
        elif error == "slow_down":
            raise OAuthSlowDownError()
        elif error == "expired_token":
            raise OAuthExpiredError()
        elif error == "access_denied":
            raise OAuthDeniedError()
        elif error:
            raise RuntimeError(f"OAuth error: {error}")

        return OAuthTokenResponse(
            access_token=d["access_token"],
            token_type=d["token_type"],
            scope=d["scope"],
        )

    # ── Authenticated API calls ──

    def create_issue(
        self, token: str, owner: str, repo: str,
        title: str, body: str, labels: list[str],
    ) -> IssueResponse:
        payload = {"title": title, "body": body}
        if labels:
            payload["labels"] = labels
        resp = self.session.post(
            f"{_API_BASE}/repos/{owner}/{repo}/issues",
            json=payload,
            headers=self._auth_headers(token),
        )
        resp.raise_for_status()
        d = resp.json()
        return IssueResponse(
            number=d["number"],
            html_url=d["html_url"],
            title=d["title"],
        )

    def list_labels(self, token: str, owner: str, repo: str) -> list[Label]:
        labels = []
        page = 1
        while True:
            resp = self.session.get(
                f"{_API_BASE}/repos/{owner}/{repo}/labels",
                params={"per_page": 100, "page": page},
                headers=self._auth_headers(token),
            )
            resp.raise_for_status()
            data = resp.json()
            if not data:
                break
            for item in data:
                labels.append(Label(
                    name=item["name"],
                    color=item["color"],
                    description=item.get("description", ""),
                ))
            page += 1
        return labels

    def list_repos(self, token: str) -> list[Repo]:
        repos = []
        page = 1
        while True:
            resp = self.session.get(
                f"{_API_BASE}/user/repos",
                params={
                    "per_page": 100,
                    "page": page,
                    "sort": "pushed",
                    "affiliation": "owner,collaborator,organization_member",
                },
                headers=self._auth_headers(token),
            )
            resp.raise_for_status()
            data = resp.json()
            if not data:
                break
            for item in data:
                repos.append(Repo(
                    owner=item["owner"]["login"],
                    name=item["name"],
                    full_name=item["full_name"],
                ))
            page += 1
        return repos


class GiteaAPI:
    """Gitea REST API v1, authenticated with a personal access token."""

    _PAGE_SIZE = 50  # Gitea's default MAX_RESPONSE_ITEMS

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.api_base = f"{self.base_url}/api/v1"
        self.session = _new_session()

    def _auth_headers(self, token: str) -> dict:
        return {"Authorization": f"token {token}"}

    def _get_paged(self, token: str, path: str, params: dict = None) -> list[dict]:
        items = []
        page = 1
        while True:
            resp = self.session.get(
                f"{self.api_base}{path}",
                params={**(params or {}), "limit": self._PAGE_SIZE, "page": page},
                headers=self._auth_headers(token),
            )
            resp.raise_for_status()
            data = resp.json()
            items.extend(data)
            if len(data) < self._PAGE_SIZE:
                return items
            page += 1

    def get_username(self, token: str) -> str:
        """Validate *token* and return the login it belongs to."""
        resp = self.session.get(
            f"{self.api_base}/user", headers=self._auth_headers(token),
        )
        resp.raise_for_status()
        return resp.json()["login"]

    def token_settings_url(self) -> str:
        return f"{self.base_url}/user/settings/applications"

    def _raw_labels(self, token: str, owner: str, repo: str) -> list[dict]:
        labels = self._get_paged(token, f"/repos/{owner}/{repo}/labels")
        # Org-wide labels are also valid on the org's repos
        try:
            labels += self._get_paged(token, f"/orgs/{owner}/labels")
        except requests.HTTPError as e:
            if e.response is None or e.response.status_code != 404:
                raise
        return labels

    def create_issue(
        self, token: str, owner: str, repo: str,
        title: str, body: str, labels: list[str],
    ) -> IssueResponse:
        payload = {"title": title, "body": body}
        if labels:
            # Gitea wants label IDs, not names
            ids = {l["name"]: l["id"] for l in self._raw_labels(token, owner, repo)}
            payload["labels"] = [ids[name] for name in labels if name in ids]
        resp = self.session.post(
            f"{self.api_base}/repos/{owner}/{repo}/issues",
            json=payload,
            headers=self._auth_headers(token),
        )
        resp.raise_for_status()
        d = resp.json()
        return IssueResponse(
            number=d["number"],
            html_url=d["html_url"],
            title=d["title"],
        )

    def list_labels(self, token: str, owner: str, repo: str) -> list[Label]:
        return [
            Label(
                name=item["name"],
                color=item["color"].lstrip("#"),
                description=item.get("description", ""),
            )
            for item in self._raw_labels(token, owner, repo)
        ]

    def list_repos(self, token: str) -> list[Repo]:
        return [
            Repo(
                owner=item["owner"]["login"],
                name=item["name"],
                full_name=item["full_name"],
            )
            for item in self._get_paged(token, "/user/repos")
            if item.get("has_issues", True) and not item.get("archived", False)
        ]


class MindBlownAPI:
    """MindBlown REST API. Maps stand in for repos, nodes for issues, tags for labels.

    Repo.owner carries the map id and Repo.name the map name. New nodes are
    added under the map's issue inbox node, or its root if it has none.

    If the map is linked to a repo (GitHub or Gitea), MindBlown is asked to
    file the issue from the node, so it links and syncs it like its own.
    """

    _TAG_COLOR = "8b949e"  # tags have no color of their own

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.session = _new_session()
        # Set by the caller from the per-repo config: should_link(map_id) -> bool
        self.should_link = lambda map_id: True

    def _auth_headers(self, token: str) -> dict:
        return {"Authorization": f"Bearer {token}"}

    def _get_map(self, token: str, map_id: str) -> dict:
        resp = self.session.get(
            f"{self.base_url}/api/maps/{map_id}",
            headers=self._auth_headers(token),
        )
        resp.raise_for_status()
        return resp.json()

    def login(self, email: str, password: str) -> tuple[str, str]:
        """Exchange credentials for a long-lived (1 year) token.

        Returns (token, display name). The password is not kept.
        """
        resp = self.session.post(
            f"{self.base_url}/api/auth/login",
            json={"email": email, "password": password},
        )
        resp.raise_for_status()
        d = resp.json()
        resp = self.session.post(
            f"{self.base_url}/api/auth/long-lived-token",
            headers=self._auth_headers(d["token"]),
        )
        resp.raise_for_status()
        return resp.json()["token"], d["user"].get("name") or d["user"]["email"]

    def create_issue(
        self, token: str, owner: str, repo: str,
        title: str, body: str, labels: list[str],
    ) -> IssueResponse:
        map_id = owner
        map_info = self._get_map(token, map_id)["map"]
        parent_id = map_info.get("githubInboxNodeId") or map_info["rootNodeId"]
        resp = self.session.post(
            f"{self.base_url}/api/maps/{map_id}/nodes",
            json={"parentId": parent_id, "text": title},
            headers=self._auth_headers(token),
        )
        resp.raise_for_status()
        node = resp.json()

        # Node creation ignores description and tags; set them afterwards
        fields = {}
        if body:
            fields["description"] = body
        if labels:
            fields["tags"] = labels
        if fields:
            resp = self.session.put(
                f"{self.base_url}/api/maps/{map_id}/nodes/{node['id']}",
                json=fields,
                headers=self._auth_headers(token),
            )
            resp.raise_for_status()

        result = IssueResponse(
            number=None,
            html_url=f"{self.base_url}/?map={map_id}",
            title=node["text"],
        )
        linked_owner = map_info.get("githubRepoOwner")
        linked_repo = map_info.get("githubRepoName")
        if not (linked_owner and linked_repo and self.should_link(map_id)):
            return result

        # The node exists now; a failure from here on must not raise, or the
        # caller would queue/retry and create a duplicate node.
        try:
            # Despite the path, this files on whichever forge (GitHub or
            # Gitea) the map is bound to, from the node's description and tags.
            resp = self.session.post(
                f"{self.base_url}/api/maps/{map_id}/nodes/{node['id']}/github/create",
                headers=self._auth_headers(token),
            )
            if resp.status_code == 400:
                error = resp.json().get("error", {})
                result.warning = (
                    f"Node created, but MindBlown could not file the issue in "
                    f"{linked_owner}/{linked_repo}: {error.get('message') or resp.text}"
                )
                return result
            resp.raise_for_status()
            issue = resp.json()["issue"]
        except Exception as e:
            result.warning = f"Node created, but the linked issue failed: {e}"
            return result
        return IssueResponse(
            number=issue["number"],
            html_url=issue["html_url"],
            title=node["text"],
        )

    def list_labels(self, token: str, owner: str, repo: str) -> list[Label]:
        nodes = self._get_map(token, owner)["nodes"]
        tags = sorted({t for n in nodes for t in n.get("tags", [])}, key=str.lower)
        return [Label(name=t, color=self._TAG_COLOR) for t in tags]

    def list_repos(self, token: str) -> list[Repo]:
        resp = self.session.get(
            f"{self.base_url}/api/maps",
            headers=self._auth_headers(token),
        )
        resp.raise_for_status()
        return [
            Repo(owner=m["id"], name=m["name"], full_name=m["name"])
            for m in resp.json()
        ]


def client_for(account: dict):
    """Return the API client for a config account entry."""
    kind = account["kind"]
    if kind == "github":
        return GitHubAPI()
    if kind == "gitea":
        return GiteaAPI(account["base_url"])
    if kind == "mindblown":
        return MindBlownAPI(account["base_url"])
    raise ValueError(f"Unknown account kind: {kind}")
