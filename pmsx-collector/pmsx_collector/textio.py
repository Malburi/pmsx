"""소스 파일 읽기 — 레거시 소스는 UTF-8 / EUC-KR(CP949) 가 섞여 있다.

UTF-8(BOM 포함)로 먼저 읽고, 깨지면 CP949(EUC-KR 상위 호환)로 읽는다. 둘 다 실패하면 CP949 로 대체 문자를 넣어 읽는다.
XML 은 선언부의 encoding 을 지운 문자열로 돌려준다(ElementTree 는 euc-kr 선언을 해석하지 못한다).
"""
from __future__ import annotations

import re
from pathlib import Path

_CACHE: dict[str, tuple[float, str]] = {}


def read_text_auto(path: Path | str) -> str:
    p = Path(path)
    key = str(p)
    try:
        mtime = p.stat().st_mtime
    except OSError:
        mtime = 0
    hit = _CACHE.get(key)
    if hit and hit[0] == mtime:
        return hit[1]
    raw = p.read_bytes()
    text = decode_auto(raw)
    if len(_CACHE) > 20000:
        _CACHE.clear()
    _CACHE[key] = (mtime, text)
    return text


def decode_auto(raw: bytes) -> str:
    for enc in ("utf-8-sig", "cp949"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("cp949", errors="replace")


def read_xml_text(path: Path | str) -> str:
    """XML 선언(<?xml … encoding="euc-kr"?>)을 지운 문자열 — ET.fromstring(str) 로 바로 파싱 가능."""
    text = read_text_auto(path)
    return re.sub(r"^\s*<\?xml[^>]*\?>", "", text, count=1)
