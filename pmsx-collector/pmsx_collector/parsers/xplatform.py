"""MiPlatform / XPlatform 화면 파서 — docs/13 §9.

두 제품은 Nexacro 의 전신이라 화면 정의·스크립트 구조가 비슷하다. 화면 파일을 Nexacro N 모양
(<Form><Objects/><Layouts><Layout>…</Layout></Layouts><Bind/><Script/>)으로 정규화한 뒤
NexacroParser 의 좌표 누적·이벤트·transaction·와이어프레임 처리를 그대로 쓴다.

XPlatform (*.xfdl, FDL 1.x)
  <Form id position="absolute l t r b" titletext onload> 아래에 컴포넌트가 바로 온다 (Layouts 없음)
  Div·Tabpage 안 컴포넌트도 바로 온다 → Layouts/Layout 으로 감싼다
  스크립트: <Script type="xscript4.0"> include "lib::x.xjs"; function fn(obj:Button, e:ClickEventInfo) { transaction(…) }
MiPlatform (*.xml, <Window><Form …>)
  속성이 대문자 시작(Id, Left, Top, Width, Height, Text, OnClick, BindDataset, Column)
  <Datasets><Dataset Id><Contents><colinfo id/> · Grid <contents><format><columns><col width/><head><cell text/><body><cell colid/>
  Tab <Contents><TabPage Id Text>…, Edit BindDataset+Column → Bind
  스크립트: <Script><![CDATA[ #include "lib::x.js"; function btn_OnClick(obj) { Transaction("svc", "svc::url", …) } ]]>
"""
from __future__ import annotations

import copy
import xml.etree.ElementTree as ET
from pathlib import Path

from .nexacro import NexacroParser

NON_COMPONENTS = {"Objects", "Bind", "Script", "Layouts", "Layout", "Datasets", "Dataset", "Contents", "InitValue",
                  "TypeDefinition", "Formats", "Format", "Tabpages", "contents", "format", "Columns", "Rows", "Band",
                  "Cell", "Column", "Row", "ColumnInfo", "Persistence"}
MI_TAGS = {"Checkbox": "CheckBox", "TabPage": "Tabpage", "Image": "ImageViewer", "Shape": "Static", "Div": "Div"}
MI_EVENTS = {"onloadcompleted": "onload"}


def _lower_attrs(el: ET.Element) -> dict[str, str]:
    out = {}
    for k, v in el.attrib.items():
        lk = k.lower()
        out[MI_EVENTS.get(lk, lk)] = v
    return out


def _wrap_layout(container: ET.Element) -> None:
    """XPlatform: 컨테이너 바로 아래 컴포넌트 → <Layouts><Layout> 로 이동 (재귀)."""
    if container.find("Layouts") is not None:
        return
    comps = [c for c in list(container) if c.tag not in NON_COMPONENTS]
    if not comps:
        return
    lays = ET.SubElement(container, "Layouts")
    lay = ET.SubElement(lays, "Layout")
    for c in comps:
        container.remove(c)
        lay.append(c)
        if c.tag == "Div":
            _wrap_layout(c)
        elif c.tag == "Tab":
            pages = c.find("Tabpages")
            for tp in (pages.findall("Tabpage") if pages is not None else []):
                _wrap_layout(tp)


def normalize_xplatform(form: ET.Element) -> ET.Element:
    form = copy.deepcopy(form)
    _wrap_layout(form)
    return form


def _mi_grid(src: ET.Element, dst: ET.Element) -> None:
    fmt = src.find("contents/format") if src.find("contents") is not None else src.find("format")
    if fmt is None:
        return
    fmts = ET.SubElement(dst, "Formats")
    f = ET.SubElement(fmts, "Format", {"id": fmt.get("id", "default")})
    cols = ET.SubElement(f, "Columns")
    for c in (fmt.find("columns").findall("col") if fmt.find("columns") is not None else []):
        ET.SubElement(cols, "Column", {"size": c.get("width", "80")})
    head = ET.SubElement(f, "Band", {"id": "head"})
    for c in (fmt.find("head").findall("cell") if fmt.find("head") is not None else []):
        ET.SubElement(head, "Cell", {"text": c.get("text", "")})
    body = ET.SubElement(f, "Band", {"id": "body"})
    for c in (fmt.find("body").findall("cell") if fmt.find("body") is not None else []):
        ET.SubElement(body, "Cell", {"text": f"bind:{c.get('colid')}" if c.get("colid") else c.get("text", "")})


def _mi_component(src: ET.Element, binds: list[tuple[str, str, str]], prefix: str = "") -> ET.Element | None:
    if src.tag in NON_COMPONENTS:
        return None
    tag = MI_TAGS.get(src.tag, src.tag)
    attrs = _lower_attrs(src)
    el = ET.Element(tag, attrs)
    cid = attrs.get("id", tag)
    if attrs.get("binddataset") and attrs.get("column") and tag != "Grid":
        binds.append((f"{prefix}{cid}", attrs["binddataset"], attrs["column"]))
    if tag == "Grid":
        _mi_grid(src, el)
    elif tag == "Div":
        inner = src.find("Contents") if src.find("Contents") is not None else src
        lays = ET.SubElement(el, "Layouts")
        lay = ET.SubElement(lays, "Layout")
        for ch in inner:
            c = _mi_component(ch, binds, f"{prefix}{cid}.")
            if c is not None:
                lay.append(c)
    elif tag == "Tab":
        pages = ET.SubElement(el, "Tabpages")
        inner = src.find("Contents") if src.find("Contents") is not None else src
        for tp in inner:
            if tp.tag not in ("TabPage", "Tabpage"):
                continue
            pa = _lower_attrs(tp)
            page = ET.SubElement(pages, "Tabpage", {"id": pa.get("id", ""), "text": pa.get("text", "")})
            lays = ET.SubElement(page, "Layouts")
            lay = ET.SubElement(lays, "Layout")
            for ch in tp:
                c = _mi_component(ch, binds, f"{prefix}{cid}.{pa.get('id', '')}.")
                if c is not None:
                    lay.append(c)
    return el


def normalize_miplatform(window: ET.Element) -> ET.Element | None:
    mform = window.find("Form") if window.tag == "Window" else window
    if mform is None:
        return None
    attrs = _lower_attrs(mform)
    form = ET.Element("Form", {k: v for k, v in attrs.items() if k in ("id", "width", "height") or k.startswith("on")})
    form.set("titletext", attrs.get("title", attrs.get("id", "")))
    objs = ET.SubElement(form, "Objects")
    for ds in mform.iter("Dataset"):
        d = ET.SubElement(objs, "Dataset", {"id": ds.get("Id", ds.get("id", ""))})
        ci = ET.SubElement(d, "ColumnInfo")
        for col in ds.iter("colinfo"):
            ET.SubElement(ci, "Column", {"id": col.get("id", "")})
    lays = ET.SubElement(form, "Layouts")
    lay = ET.SubElement(lays, "Layout")
    binds: list[tuple[str, str, str]] = []
    for ch in mform:
        c = _mi_component(ch, binds)
        if c is not None:
            lay.append(c)
    if binds:
        b = ET.SubElement(form, "Bind")
        for comp, ds, col in binds:
            ET.SubElement(b, "BindItem", {"compid": comp, "propid": "value", "datasetid": ds, "columnid": col})
    script = window.find("Script") if window.find("Script") is not None else mform.find("Script")
    if script is not None:
        s = ET.SubElement(form, "Script")
        s.text = script.text
    return form


class XPlatformParser(NexacroParser):
    name = "xplatform"
    layer = "ui"
    source_key = "xplatform"
    cfg_ns = "xplatform"
    tx_names = ("transaction", "Transaction")

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("*.xfdl")) or any("<Window" in p.read_text(encoding="utf-8", errors="ignore")[:400]
                                                        for p in root.rglob("*.xml")) else 0.0

    def load_forms(self) -> list[tuple[Path, ET.Element]]:
        out = []
        files = sorted([*self.root.rglob("*.xfdl"), *self.root.rglob("*.xml")])
        for f in files:
            if f.name.lower() in ("typedefinition.xml", "default_typedef.xml"):
                continue
            try:
                doc = ET.fromstring(f.read_bytes())
            except ET.ParseError as e:
                self.ctx.model.fail("parse", self.ctx.config.rel(f), f"화면 XML 파싱 실패: {e}")
                continue
            if f.suffix.lower() == ".xfdl":
                form = doc.find("Form") if doc.tag == "FDL" else (doc if doc.tag == "Form" else None)
                if form is not None:
                    out.append((f, normalize_xplatform(form)))
            elif doc.tag == "Window":
                form = normalize_miplatform(doc)
                if form is not None:
                    out.append((f, form))
        return out

    def ui_tech(self, f: Path) -> str:
        return "xplatform" if f.suffix.lower() == ".xfdl" else "miplatform"

    def script_lang(self, f: Path) -> str:
        return "xfdl" if f.suffix.lower() == ".xfdl" else "xml"
