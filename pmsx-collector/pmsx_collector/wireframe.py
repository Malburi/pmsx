"""소스 좌표 기반 와이어프레임 PNG — 런타임 없이 화면 모양을 재구성한다 (Nexacro XFDL 등).

입력 명세(ctx.wireframes[화면키]):
  {"width": W, "height": H, "title": "급여계산",
   "items": [{"kind": "button|edit|combo|calendar|check|radio|grid|tab|div|static|text|other",
              "box": [x, y, w, h], "text": "조회", "cols": [("사번", 100), ...], "tabs": ["지급내역", ...]}]}
좌표는 화면 원점 기준 CSS 픽셀 — 핫스팟(elements.box)과 같은 좌표계라 별도 변환이 필요 없다.
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

FONT_CANDIDATES = [
    "C:/Windows/Fonts/malgun.ttf", "C:/Windows/Fonts/gulim.ttc", "C:/Windows/Fonts/batang.ttc",
    "/usr/share/fonts/truetype/nanum/NanumGothic.ttf", "/usr/share/fonts/nanum/NanumGothic.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "/System/Library/Fonts/AppleSDGothicNeo.ttc",
]

C = {
    "bg": (236, 239, 244), "panel": (247, 249, 252), "panel_line": (184, 194, 208),
    "btn": (243, 245, 248), "btn_line": (125, 136, 154), "btn_top": (253, 253, 253),
    "edit": (255, 255, 255), "edit_line": (138, 148, 166), "text": (34, 34, 34), "muted": (110, 118, 130),
    "head": (201, 210, 223), "head_line": (154, 165, 181), "row_alt": (245, 247, 250), "row_line": (213, 219, 228),
    "title": (40, 62, 98), "tab_on": (255, 255, 255), "tab_off": (220, 226, 235),
}


@lru_cache(maxsize=8)
def _font(size: int, font_path: str | None = None):
    from PIL import ImageFont
    for cand in ([font_path] if font_path else []) + FONT_CANDIDATES:
        if cand and Path(cand).is_file():
            try:
                return ImageFont.truetype(cand, size)
            except OSError:
                continue
    try:
        return ImageFont.load_default(size)
    except TypeError:  # pragma: no cover - 구버전 Pillow
        return ImageFont.load_default()


def _fit(draw, text: str, font, max_w: float) -> str:
    if not text:
        return ""
    if draw.textlength(text, font=font) <= max_w:
        return text
    while text and draw.textlength(text + "…", font=font) > max_w:
        text = text[:-1]
    return text + "…" if text else ""


def _center_text(draw, box, text, font, fill):
    x, y, w, h = box
    t = _fit(draw, text, font, w - 6)
    tw = draw.textlength(t, font=font)
    draw.text((x + (w - tw) / 2, y + h / 2), t, font=font, fill=fill, anchor="lm")


def render(spec: dict, path: Path, font_path: str | None = None) -> tuple[int, int]:
    """명세 → PNG. 반환: (너비, 높이)."""
    from PIL import Image, ImageDraw

    W, H = int(spec["width"]), int(spec["height"])
    img = Image.new("RGB", (max(W, 1), max(H, 1)), C["bg"])
    d = ImageDraw.Draw(img)
    f12, f11, f15 = _font(12, font_path), _font(11, font_path), _font(15, font_path)

    order = {"div": 0, "tab": 1, "grid": 2}
    for it in sorted(spec.get("items", []), key=lambda i: order.get(i["kind"], 5)):
        x, y, w, h = [float(v) for v in it["box"]]
        if w <= 0 or h <= 0:
            continue
        r = [x, y, x + w - 1, y + h - 1]
        k, text = it["kind"], it.get("text") or ""
        if k == "div":
            d.rectangle(r, fill=C["panel"], outline=C["panel_line"])
        elif k == "tab":
            tabs = it.get("tabs") or [""]
            tw = min(140.0, max(60.0, w / max(len(tabs), 1)))
            for i, name in enumerate(tabs):
                tb = [x + i * tw, y, x + (i + 1) * tw - 2, y + 25]
                d.rectangle(tb, fill=C["tab_on"] if i == 0 else C["tab_off"], outline=C["panel_line"])
                _center_text(d, (tb[0], tb[1], tw - 2, 25), name, f12, C["text"])
            d.rectangle([x, y + 25, x + w - 1, y + h - 1], fill=C["panel"], outline=C["panel_line"])
        elif k == "grid":
            d.rectangle(r, fill=C["edit"], outline=C["edit_line"])
            cols = it.get("cols") or []
            total = sum(c[1] for c in cols) or 1
            head_h = 26
            d.rectangle([x, y, x + w - 1, y + head_h], fill=C["head"], outline=C["head_line"])
            ry, i = y + head_h, 0
            while ry + 24 <= y + h - 1 and i < 30:
                if i % 2:
                    d.rectangle([x + 1, ry, x + w - 2, ry + 24], fill=C["row_alt"])
                d.line([x, ry + 24, x + w - 1, ry + 24], fill=C["row_line"])
                ry, i = ry + 24, i + 1
            cx = x
            for name, size in cols:          # 열 구분선·머리글은 행 배경 위에
                cw = w * size / total
                if cx > x:
                    d.line([cx, y, cx, y + h - 1], fill=C["row_line"])
                _center_text(d, (cx, y, cw, head_h), name, f11, C["text"])
                cx += cw
        elif k == "button":
            d.rectangle(r, fill=C["btn"], outline=C["btn_line"])
            d.line([x + 1, y + 1, x + w - 2, y + 1], fill=C["btn_top"])
            _center_text(d, (x, y, w, h), text, f12, C["text"])
        elif k in ("edit", "combo", "calendar", "other"):
            d.rectangle(r, fill=C["edit"], outline=C["edit_line"])
            pad = 0.0
            if k in ("combo", "calendar"):
                bw = min(20.0, h)
                d.rectangle([x + w - bw, y, x + w - 1, y + h - 1], fill=C["btn"], outline=C["edit_line"])
                if k == "combo":
                    cx, cy = x + w - bw / 2, y + h / 2
                    d.polygon([(cx - 4, cy - 2), (cx + 4, cy - 2), (cx, cy + 3)], fill=C["muted"])
                else:
                    d.rectangle([x + w - bw + 5, y + h / 2 - 5, x + w - 6, y + h / 2 + 5], outline=C["muted"])
                pad = bw
            if text:
                t = _fit(d, text, f12, w - pad - 8)
                d.text((x + 4, y + h / 2), t, font=f12, fill=C["muted"], anchor="lm")
        elif k in ("check", "radio"):
            s = min(13.0, h - 2)
            by = y + (h - s) / 2
            if k == "check":
                d.rectangle([x, by, x + s, by + s], fill=C["edit"], outline=C["edit_line"])
            else:
                d.ellipse([x, by, x + s, by + s], fill=C["edit"], outline=C["edit_line"])
            d.text((x + s + 5, y + h / 2), _fit(d, text, f12, w - s - 6), font=f12, fill=C["text"], anchor="lm")
        elif k == "title":
            d.text((x, y + h / 2), _fit(d, text, f15, w), font=f15, fill=C["title"], anchor="lm")
        else:  # static / text
            d.text((x, y + h / 2), _fit(d, text, f12, w), font=f12, fill=C["text"], anchor="lm")
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)
    return img.size
