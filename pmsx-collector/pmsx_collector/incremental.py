"""증분 수집 (FR-CL-021) — 이전 회차 상태(out/.state.json)와 비교해 바뀐 것만 다시 처리한다.

판단 단위
  · 소스 파일   : 소스 루트 아래 모든 파일의 SHA-1 (산출물·의존성 폴더 제외)
  · 화면        : 화면 소스 + 화면 이벤트에서 calls 로 닿는 화면 코드 파일 + 정적 요소·연결 조각의 해시
  · 캡처 이미지 : 이전 회차 PNG 의 SHA-1 (지워졌거나 바뀌었으면 다시 캡처)

처리
  1) 설정·수집기 버전이 같고 소스 변경이 없으면 → 이전 model.json 을 새 회차로 재사용 (정적 분석·캡처 생략)
  2) 소스가 바뀌었으면 → 정적 분석은 전체 재실행 (호출·Mapper·라우트 교차 참조를 다시 맞추기 위해. 수 초 단위)
                       → 동적 캡처는 화면 해시가 바뀐 화면만 다시 하고, 나머지는 이전 캡처 결과를 그대로 쓴다
결과 model.json 은 항상 전체 화면을 담는다 (서버 diff 가 정확하도록).
"""
from __future__ import annotations

import dataclasses
import hashlib
import json
import shutil
from dataclasses import dataclass, field
from pathlib import Path

from . import MODEL_VERSION, __version__
from .capture_types import Blocked, CaptureResult, DynElement, Navigation, Observed, ScreenCapture

STATE_FILE = ".state.json"
STATE_VERSION = 1
SKIP_DIRS = {".git", ".svn", ".hg", "node_modules", "__pycache__", ".venv", "venv", ".idea", ".vs", ".vscode",
             "bin", "obj", "target", "build", ".gradle", ".pytest_cache"}
FRONT_LANGS = {"js", "ts", "jsp", "xfdl", "xml", "html", "vue", "jsx", "tsx", "xjs", "aspx", "cshtml", "cs", "vb"}


def sha1_bytes(b: bytes) -> str:
    return hashlib.sha1(b).hexdigest()


def sha1_file(p: Path) -> str:
    h = hashlib.sha1()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def config_hash(cfg) -> str:
    raw = json.dumps(cfg.raw, ensure_ascii=False, sort_keys=True, default=str)
    return sha1_bytes(f"{raw}|{__version__}|{MODEL_VERSION}".encode("utf-8"))


def scan_files(cfg) -> dict[str, str]:
    """소스 루트 기준 상대경로(슬래시) → SHA-1. 출력 폴더·의존성·빌드 산출 폴더는 제외."""
    root = cfg.source_root
    out_root = cfg.resolve(cfg.get("output", "out"))
    files: dict[str, str] = {}
    if not root or not root.is_dir():
        return files
    stack = [root]
    while stack:
        d = stack.pop()
        try:
            entries = list(d.iterdir())
        except OSError:
            continue
        for p in entries:
            if p.is_dir():
                if p.name in SKIP_DIRS or (out_root and p.resolve() == out_root):
                    continue
                stack.append(p)
            elif p.is_file():
                try:
                    files[p.relative_to(root).as_posix()] = sha1_file(p)
                except OSError:
                    continue
    return files


def diff_files(prev: dict[str, str], cur: dict[str, str]) -> dict[str, list[str]]:
    return {"added": sorted(set(cur) - set(prev)),
            "removed": sorted(set(prev) - set(cur)),
            "modified": sorted(k for k in set(cur) & set(prev) if cur[k] != prev[k])}


# ---------------------------------------------------------------- 화면 의존성


class _SuffixIndex:
    """코드 단위 file 값(/js/hr/a.js, PY/PY0101.xfdl …)을 실제 소스 파일로 찾는다 — 경로 끝부분 일치."""

    def __init__(self, files: dict[str, str]):
        self.by_name: dict[str, list[str]] = {}
        for rel in files:
            self.by_name.setdefault(rel.rsplit("/", 1)[-1].lower(), []).append(rel)

    def find(self, file: str) -> list[str]:
        f = file.replace("\\", "/").lstrip("/").lower()
        if not f:
            return []
        cands = self.by_name.get(f.rsplit("/", 1)[-1], [])
        return [c for c in cands if c.lower() == f or c.lower().endswith("/" + f)]


def screen_hashes(m, files: dict[str, str]) -> dict[str, str]:
    """화면 키 → 해시. 화면 소스·화면 코드 파일 내용과 정적 요소·이벤트 조각이 하나라도 바뀌면 달라진다."""
    idx = _SuffixIndex(files)
    out_edges: dict[str, list[dict]] = {}
    for e in m.edges.values():
        out_edges.setdefault(e["from"], []).append(e)
    by_screen: dict[str, list[dict]] = {}
    for el in m.elements.values():
        by_screen.setdefault(el["screenKey"], []).append(el)

    result: dict[str, str] = {}
    for skey, s in m.screens.items():
        frag: list = [skey, s.get("url"), s.get("sourceFile"), s.get("kind")]
        dep_files: set[str] = set()
        if s.get("sourceFile"):
            dep_files.update(idx.find(s["sourceFile"]))
        starts = [skey] + [el["key"] for el in sorted(by_screen.get(skey, []), key=lambda e: e["key"])]
        for el in sorted(by_screen.get(skey, []), key=lambda e: e["key"]):
            frag.append([el["key"], el.get("label"), el.get("type"), el.get("binding"), el.get("_sbox")])
        seen: set[str] = set()
        frontier = list(starts)
        for _ in range(8):
            nxt = []
            for k in frontier:
                for e in sorted(out_edges.get(k, []), key=lambda x: (x["kind"], x["to"])):
                    if e["kind"] not in ("event", "calls", "request", "navigates"):
                        continue
                    frag.append([e["from"], e["to"], e["kind"], e.get("event"), e.get("condition")])
                    t = e["to"]
                    if e["kind"] in ("event", "calls") and t.startswith("fn:") and t not in seen:
                        seen.add(t)
                        cu = m.code_units.get(t)
                        if cu and cu.get("lang") in FRONT_LANGS:
                            frag.append([t, cu.get("lineStart"), cu.get("lineEnd")])
                            dep_files.update(idx.find(cu["file"]) or [])
                            nxt.append(t)
            frontier = nxt
        dep = sorted((f, files.get(f, "")) for f in dep_files)
        result[skey] = sha1_bytes(json.dumps([frag, dep], ensure_ascii=False, sort_keys=True, default=str).encode("utf-8"))
    return result


# ---------------------------------------------------------------- 캡처 결과 직렬화


def capture_to_dict(sc: ScreenCapture) -> dict:
    return dataclasses.asdict(sc)


def capture_from_dict(d: dict) -> ScreenCapture:
    d = dict(d)
    d["elements"] = [DynElement(**x) for x in d.get("elements", [])]
    d["observed"] = [Observed(**x) for x in d.get("observed", [])]
    d["navigations"] = [Navigation(**x) for x in d.get("navigations", [])]
    d["blocked"] = [Blocked(**x) for x in d.get("blocked", [])]
    known = {f.name for f in dataclasses.fields(ScreenCapture)}
    return ScreenCapture(**{k: v for k, v in d.items() if k in known})


# ---------------------------------------------------------------- 상태 파일


@dataclass
class State:
    sys_code: str
    config_hash: str
    run_id: str
    files: dict[str, str] = field(default_factory=dict)
    screens: dict[str, dict] = field(default_factory=dict)       # skey -> {hash, url, png, pngSha}
    capture: list[dict] = field(default_factory=list)            # ScreenCapture dict 목록 (화면 키가 있는 것만)
    capture_mode: str = "none"                                   # none | web | desktop
    warnings: list[dict] = field(default_factory=list)

    @staticmethod
    def path(out_root: Path) -> Path:
        return out_root / STATE_FILE

    @classmethod
    def load(cls, out_root: Path) -> "State | None":
        p = cls.path(out_root)
        if not p.is_file():
            return None
        try:
            d = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if d.get("version") != STATE_VERSION:
            return None
        return cls(d["sysCode"], d["configHash"], d["runId"], d.get("files", {}), d.get("screens", {}),
                   d.get("capture", []), d.get("captureMode", "none"), d.get("warnings", []))

    def save(self, out_root: Path) -> Path:
        out_root.mkdir(parents=True, exist_ok=True)
        p = self.path(out_root)
        p.write_text(json.dumps({"version": STATE_VERSION, "sysCode": self.sys_code, "configHash": self.config_hash,
                                 "runId": self.run_id, "captureMode": self.capture_mode, "files": self.files,
                                 "screens": self.screens, "capture": self.capture, "warnings": self.warnings},
                                ensure_ascii=False), encoding="utf-8")
        return p

    def run_dir(self, out_root: Path) -> Path:
        return out_root / self.run_id

    def cached_capture(self, out_root: Path, skey: str) -> ScreenCapture | None:
        """이전 캡처 결과(화면 단위). PNG 가 없어졌거나 바뀌었으면 None."""
        info = self.screens.get(skey) or {}
        sc_d = next((c for c in self.capture if c.get("screen_key") == skey), None)
        if sc_d is None or sc_d.get("error"):
            return None                      # 이전에 실패·부분 실패한 화면은 다시 캡처
        if sc_d.get("file"):
            png = self.run_dir(out_root) / sc_d["file"]
            if not png.is_file() or (info.get("pngSha") and sha1_file(png) != info["pngSha"]):
                return None
        return capture_from_dict(sc_d)


@dataclass
class IncrementalInfo:
    """report.html·통계에 남길 증분 수집 요약."""
    enabled: bool = False
    mode: str = "full"                       # full | reuse | partial
    reason: str = ""
    prev_run_id: str | None = None
    changed: dict[str, list[str]] = field(default_factory=lambda: {"added": [], "removed": [], "modified": []})
    recaptured: list[str] = field(default_factory=list)
    reused: list[str] = field(default_factory=list)

    def stats(self) -> dict[str, float]:
        if not self.enabled:
            return {}
        return {"incremental": 1 if self.mode != "full" else 0,
                "incrementalChangedFiles": sum(len(v) for v in self.changed.values()),
                "incrementalScreensRecaptured": len(self.recaptured),
                "incrementalScreensReused": len(self.reused)}

    def to_dict(self) -> dict:
        return dataclasses.asdict(self)


def copy_capture_png(prev_dir: Path, new_dir: Path, rel: str | None) -> None:
    if not rel:
        return
    src, dst = prev_dir / rel, new_dir / rel
    if src.is_file():
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def capture_state(result: CaptureResult | None, run_dir: Path, hashes: dict[str, str]) -> tuple[list[dict], dict[str, dict]]:
    """새 회차 캡처 결과 → 상태 파일용 (capture 목록, screens 정보)."""
    caps: list[dict] = []
    screens: dict[str, dict] = {k: {"hash": h} for k, h in hashes.items()}
    if result is None:
        return caps, screens
    for sc in result.screens:
        if not sc.screen_key:
            continue
        caps.append(capture_to_dict(sc))
        info = screens.setdefault(sc.screen_key, {})
        info["url"] = sc.url
        if sc.file and (run_dir / sc.file).is_file():
            info["png"] = sc.file
            info["pngSha"] = sha1_file(run_dir / sc.file)
    return caps, screens
