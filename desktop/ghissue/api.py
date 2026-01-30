"""GitHub API client using requests."""

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
    number: int
    html_url: str
    title: str


class OAuthPendingError(Exception):
    """Authorization is still pending."""


class OAuthSlowDownError(Exception):
    """Polling too fast, back off."""


class OAuthExpiredError(Exception):
    """Device code has expired."""


class OAuthDeniedError(Exception):
    """User denied access."""


class GitHubAPI:
    def __init__(self):
        self.session = requests.Session()
        self.session.headers.update({
            "Accept": "application/json",
            "User-Agent": "ghissue-desktop/1.0",
        })

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
