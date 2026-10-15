"""`upload` — 결과 패키지를 PMS-X 서버에 올린다 (망이 연결된 경우 선택 기능, docs/08 §1·§2·§3).

  POST /api/v1/auth/login                → accessToken
  GET  /api/v1/projects                  → 내가 볼 수 있는 프로젝트
  GET  /api/v1/projects/{p}/systems      → 시스템 코드로 systemId 찾기
  POST /api/v1/systems/{id}/runs         → multipart file=zip → RunSummary

비밀번호는 환경변수로만 받는다 (명령줄·설정 파일에 남기지 않기 위해).
"""
from __future__ import annotations

import json
import os
import ssl
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path


class UploadError(Exception):
    pass


@dataclass
class Client:
    server: str
    token: str | None = None
    timeout: float = 60
    ca_file: str | None = None

    @property
    def api(self) -> str:
        s = self.server.rstrip("/")
        return s if s.endswith("/api/v1") else s + "/api/v1"

    def _ctx(self):
        return ssl.create_default_context(cafile=self.ca_file) if self.ca_file else None

    def request(self, method: str, path: str, body: dict | None = None, data: bytes | None = None,
                content_type: str | None = None):
        headers = {"Accept": "application/json", "User-Agent": "pmsx-collector"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(self.api + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout, context=self._ctx()) as r:
                raw = r.read()
                return json.loads(raw.decode("utf-8")) if raw else None
        except urllib.error.HTTPError as e:
            try:
                err = json.loads(e.read().decode("utf-8"))
                msg = f"{err.get('code', e.code)}: {err.get('message', '')}"
            except (ValueError, UnicodeDecodeError):
                msg = f"HTTP {e.code}"
            raise UploadError(f"{method} {path} 실패 — {msg}") from None
        except (urllib.error.URLError, OSError) as e:
            raise UploadError(f"서버에 연결할 수 없습니다 ({self.api}): {getattr(e, 'reason', e)}") from None


def _multipart(field: str, path: Path) -> tuple[bytes, str]:
    boundary = "----pmsx" + uuid.uuid4().hex
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{path.name}\"\r\n"
            f"Content-Type: application/zip\r\n\r\n").encode("utf-8")
    tail = f"\r\n--{boundary}--\r\n".encode("utf-8")
    return head + path.read_bytes() + tail, f"multipart/form-data; boundary={boundary}"


def latest_package(out_root: Path, sys_code: str) -> Path:
    zips = sorted(out_root.glob(f"pmsx-{sys_code}-*.zip"), key=lambda p: p.stat().st_mtime)
    if not zips:
        raise UploadError(f"{out_root} 에 pmsx-{sys_code}-*.zip 결과 패키지가 없습니다. 먼저 run 을 실행하세요.")
    return zips[-1]


def upload(server: str, system_code: str, user: str, package: Path, password_env: str = "PMSX_UPLOAD_PW",
           project: str | None = None, ca_file: str | None = None, timeout: float = 120) -> dict:
    password = os.environ.get(password_env)
    if not password:
        raise UploadError(f"환경변수 {password_env} 에 비밀번호를 넣고 다시 실행하세요.")
    if not package.is_file():
        raise UploadError(f"결과 패키지가 없습니다: {package}")
    c = Client(server, timeout=timeout, ca_file=ca_file)
    login = c.request("POST", "/auth/login", {"loginId": user, "password": password})
    c.token = (login or {}).get("accessToken")
    if not c.token:
        raise UploadError("로그인 응답에 accessToken 이 없습니다.")
    projects = c.request("GET", "/projects") or []
    if project:
        projects = [p for p in projects if project in (p.get("projectId"), p.get("name"))]
        if not projects:
            raise UploadError(f"프로젝트 '{project}' 를 찾지 못했거나 권한이 없습니다.")
    matches = []
    for p in projects:
        for s in c.request("GET", f"/projects/{p['projectId']}/systems") or []:
            if s.get("code") == system_code:
                matches.append((p, s))
    if not matches:
        raise UploadError(f"시스템 코드 {system_code} 를 찾지 못했습니다. 서버의 시스템 관리 화면에서 먼저 등록하세요.")
    if len(matches) > 1:
        names = ", ".join(f"{p['name']}({p['projectId']})" for p, _ in matches)
        raise UploadError(f"시스템 코드 {system_code} 가 여러 프로젝트에 있습니다: {names} — --project 로 지정하세요.")
    proj, system = matches[0]
    data, ctype = _multipart("file", package)
    run = c.request("POST", f"/systems/{system['systemId']}/runs", data=data, content_type=ctype)
    return {"project": proj, "system": system, "run": run}
