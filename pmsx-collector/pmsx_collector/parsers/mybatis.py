"""MyBatis/iBatis Mapper XML 파서 — 동적 SQL 정규화 → SQL 분석 → accesses 엣지."""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

from collections import Counter

from ..keys import short_namespace, sp_key, sql_key, tbl_key
from . import Context
from .sql_analyzer import analyze

STMT_TAGS = {"select": "select", "insert": "insert", "update": "update", "delete": "delete"}
# iBatis 2 (sqlMap) 호환
IBATIS_TAGS = {"statement": "other", "procedure": "procedure"}


IBATIS_COND = {"isNotEmpty", "isNotNull", "isEmpty", "isNull", "isEqual", "isNotEqual", "isGreaterThan",
               "isGreaterEqual", "isLessThan", "isLessEqual", "isPropertyAvailable", "isNotPropertyAvailable",
               "isParameterPresent", "isNotParameterPresent"}


def flatten(node: ET.Element, fragments: dict[str, ET.Element], depth: int = 0, drop_first_prepend: bool = False) -> str:
    """동적 SQL 을 '모든 조건 참' 기준 한 문장으로 펼친다."""
    if depth > 10:
        return ""
    first_cond = True
    out: list[str] = [node.text or ""]
    for child in node:
        tag = child.tag
        inner = ""
        if tag == "include":
            frag = fragments.get(child.get("refid", ""))
            inner = flatten(frag, fragments, depth + 1) if frag is not None else ""
        elif tag == "dynamic":
            # iBatis: <dynamic prepend="WHERE"> 는 첫 하위 조건의 prepend(AND/OR)를 자신의 prepend 로 대체
            body = flatten(child, fragments, depth + 1, drop_first_prepend=True).strip()
            inner = f" {child.get('prepend', '')} {body} " if body else ""
        elif tag in IBATIS_COND:
            body = flatten(child, fragments, depth + 1)
            pre = "" if (drop_first_prepend and first_cond) else child.get("prepend", "")
            first_cond = False
            inner = f" {pre} {body} "
        elif tag in ("if", "when", "otherwise"):
            inner = flatten(child, fragments, depth + 1)
        elif tag == "choose":
            # Element 는 자식이 없으면 거짓으로 평가되므로 `or` 대신 None 비교
            first = next((c for c in child if c.tag == "when"), None)
            if first is None:
                first = next((c for c in child if c.tag == "otherwise"), None)
            inner = flatten(first, fragments, depth + 1) if first is not None else ""
        elif tag == "where":
            body = flatten(child, fragments, depth + 1).strip()
            body = re.sub(r"^(AND|OR)\s+", "", body, flags=re.I)
            inner = f" WHERE {body} " if body else ""
        elif tag == "set":
            body = flatten(child, fragments, depth + 1).strip().rstrip(",")
            inner = f" SET {body} "
        elif tag == "trim":
            body = flatten(child, fragments, depth + 1).strip()
            for ov in (child.get("prefixOverrides") or "").split("|"):
                if ov and body.upper().startswith(ov.strip().upper()):
                    body = body[len(ov.strip()):]
            for ov in (child.get("suffixOverrides") or "").split("|"):
                if ov and body.upper().endswith(ov.strip().upper()):
                    body = body[: -len(ov.strip())]
            inner = f" {child.get('prefix', '')} {body} {child.get('suffix', '')} "
        elif tag in ("foreach", "iterate"):
            inner = f" {child.get('open', '')} {flatten(child, fragments, depth + 1)} {child.get('close', '')} "
        elif tag in ("selectKey", "bind"):
            inner = ""
        else:
            inner = flatten(child, fragments, depth + 1)
        out.append(inner)
        out.append(child.tail or "")
    return "".join(out)


def to_plain_sql(text: str) -> str:
    text = re.sub(r"#\{\s*([\w.]+)[^}]*\}", r":\1", text)       # #{empNo, jdbcType=..} -> :empNo
    text = re.sub(r"\$\{\s*([\w.]+)[^}]*\}", r"\1", text)        # ${col} -> col
    text = re.sub(r"#([\w.]+)#", r":\1", text)                    # iBatis #param#
    text = re.sub(r":(\w+)\.(\w+)", r":\1_\2", text)              # :row.sortOrd -> :row_sortOrd
    return re.sub(r"[ \t]+", " ", re.sub(r"\n\s*\n+", "\n", text)).strip()


class MyBatisParser:
    name = "mybatis"
    layer = "sql"

    def detect(self, root: Path) -> float:
        return 1.0 if any("<mapper" in p.read_text(encoding="utf-8", errors="ignore")[:500] for p in root.rglob("*.xml")) else 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        root = cfg.source_dir("mapper")
        if not root or not root.is_dir():
            m.fail("parse", "source.mapper", "Mapper XML 경로 없음")
            return
        docs: list[tuple[Path, str, ET.Element, str]] = []
        for f in sorted(root.rglob("*.xml")):
            raw = f.read_text(encoding="utf-8")
            try:
                # 외부 DTD 를 읽지 않도록 DOCTYPE 제거
                doc = ET.fromstring(re.sub(r"<!DOCTYPE[^>]*>", "", raw, count=1).encode("utf-8"))
            except ET.ParseError as e:
                m.fail("parse", cfg.rel(f), f"XML 파싱 실패: {e}")
                continue
            if doc.tag in ("mapper", "sqlMap"):
                docs.append((f, raw, doc, doc.get("namespace", f.stem)))
        # 키 이름: 단축 네임스페이스. 같은 단축 이름이 둘 이상이면 전체 네임스페이스로 (스키마 v0.2)
        shorts = Counter(short_namespace(ns) for ns in {d[3] for d in docs})
        for _, _, _, ns in docs:
            ctx.mapper_namespaces[ns] = short_namespace(ns) if shorts[short_namespace(ns)] == 1 else ns
        # <sql id> 조각은 다른 Mapper 에서도 <include refid="ns.id"> 로 참조할 수 있다
        for _, _, doc, ns in docs:
            for frag in doc.iter("sql"):
                if frag.get("id"):
                    ctx.sql_fragments[f"{ns}.{frag.get('id')}"] = frag
        for f, raw, doc, ns in docs:
            self._file(ctx, f, raw, doc, ns, shorts)

    def _file(self, ctx: Context, f: Path, raw: str, doc: ET.Element, ns: str, shorts: Counter):
        cfg, m = ctx.config, ctx.model
        name = ctx.mapper_namespaces[ns]
        fragments = dict(ctx.sql_fragments)
        fragments.update({s.get("id"): s for s in doc.iter("sql") if s.get("id")})
        for node in doc:
            kind = STMT_TAGS.get(node.tag) or IBATIS_TAGS.get(node.tag)
            sid = node.get("id")
            if not kind or not sid:
                continue
            key = sql_key(name, sid)
            ctx.sql_by_ref[f"{ns}.{sid}"] = key
            if shorts[short_namespace(ns)] == 1:
                ctx.sql_by_ref[f"{short_namespace(ns)}.{sid}"] = key
            line = next((i + 1 for i, ln in enumerate(raw.splitlines()) if re.search(rf'\bid\s*=\s*"{re.escape(sid)}"', ln)), None)
            flat = flatten(node, fragments)
            for inc in node.iter("include"):
                if inc.get("refid") and inc.get("refid") not in fragments:
                    m.fail("parse", key, f"<include refid=\"{inc.get('refid')}\"> 대상 <sql> 조각을 찾지 못함")
            dollars = sorted(set(re.findall(r"\$\{\s*([\w.]+)", "".join(node.itertext()))))   # 모든 분기 검사
            if dollars:
                m.warn("sql", key, f"${{}} 문자열 치환 사용 ({', '.join(dollars)}) — SQL 인젝션 점검 대상, 컬럼·테이블 분석이 부정확할 수 있음")
            text = to_plain_sql(flat)
            info = analyze(text, ctx.table_cols)
            if info.procedures:
                kind = "procedure"
            entry = {"key": key, "kind": kind, "namespace": ns, "file": cfg.rel(f)}
            if line:
                entry["line"] = line
            if cfg.include_snippets:
                entry["text"] = text
            m.sql[key] = entry
            ev = f"{cfg.rel(f)}:{line}" if line else cfg.rel(f)
            for proc in info.procedures:
                m.add_edge(key, sp_key(cfg.schema, proc), "calls", confidence=0.99, evidence=ev)
            for t, v in info.tables.items():
                tkey = tbl_key(cfg.schema, t)
                if tkey not in m.tables:
                    m.add_table(tkey, cfg.schema, t, comment="DDL 미확인")
                    m.fail("parse", key, f"DDL에 없는 테이블 참조: {t}")
                crud = "".join(c for c in "CRUD" if c in v["crud"])
                m.add_edge(key, tkey, "accesses", confidence=0.95 if info.parsed else 0.7,
                           crud=crud or "R", columns=sorted(v["columns"]) or None, evidence=ev)
            if not info.parsed:
                m.fail("parse", key, "SQL 구문 분석 실패 → 정규식으로 테이블만 추정")
            for a, b in info.joins:
                ctx.join_evidence.append((key, a, b))
