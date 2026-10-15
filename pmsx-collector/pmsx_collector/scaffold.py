"""`init` — 소스 루트를 훑어 화면·서버 기술을 감지하고 collector.yaml 초안을 만든다 (비대화식).

감지는 파일 확장자 + 내용 시그니처로 한다. 근거는 생성 파일에 주석으로 남긴다.
캡처는 항상 꺼진 상태(enabled: false)로 만든다 — 테스트계 URL·계정·운영계 차단 목록을 사람이 확인한 뒤 켠다.
"""
from __future__ import annotations

import os
import re
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

SKIP_DIRS = {".git", ".svn", "node_modules", "__pycache__", ".venv", "venv", ".idea", ".vs", ".vscode",
             "bin", "obj", "target", "build", "dist", ".gradle", "out", "vendor"}
MAX_FILES = 60000
READ_LIMIT = 400_000          # 파일당 읽는 최대 바이트 (시그니처 판별용)

TEXT_EXT = {".jsp", ".js", ".xjs", ".xfdl", ".xml", ".vue", ".jsx", ".tsx", ".ts", ".java", ".cs", ".aspx",
            ".cshtml", ".sql", ".csv", ".html", ".json", ".config", ".csproj"}


def _read(p: Path) -> str:
    try:
        with open(p, "rb") as f:
            b = f.read(READ_LIMIT)
    except OSError:
        return ""
    for enc in ("utf-8", "cp949"):
        try:
            return b.decode(enc)
        except UnicodeDecodeError:
            continue
    return b.decode("utf-8", "ignore")


def _common_dir(paths: list[Path]) -> Path | None:
    if not paths:
        return None
    try:
        return Path(os.path.commonpath([str(p) for p in paths]))
    except ValueError:
        return None


@dataclass
class Detection:
    root: Path
    ui: dict[str, list[str]] = field(default_factory=dict)          # 기술 → 근거
    server: dict[str, list[str]] = field(default_factory=dict)
    db_type: str | None = None
    db_evidence: list[str] = field(default_factory=list)
    source: dict[str, Path] = field(default_factory=dict)           # source.<키> → 경로
    ddl_files: list[Path] = field(default_factory=list)
    menu: dict = field(default_factory=dict)                        # menu 섹션 (경로는 Path)
    front_apis: dict[str, dict] = field(default_factory=dict)
    front_navs: dict[str, dict] = field(default_factory=dict)
    extra: dict[str, dict] = field(default_factory=dict)            # nexacro/xplatform/websquare/spa/dotnet/capture 등
    notes: list[str] = field(default_factory=list)

    def add(self, kind: str, tech: str, why: str):
        bucket = self.ui if kind == "ui" else self.server
        bucket.setdefault(tech, [])
        if why not in bucket[tech] and len(bucket[tech]) < 4:
            bucket[tech].append(why)


# ---------------------------------------------------------------- 공통 래퍼 함수 추정

_FN_DEF = re.compile(r"(?:^|\n)\s*(?:function\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)"
                     r"|(?:this\.|var\s+|let\s+|const\s+)?([A-Za-z_$][\w$.]*)\s*=\s*function\s*\(([^)]*)\))\s*\{")


def _functions(text: str) -> list[tuple[str, list[str], str]]:
    out = []
    for m in _FN_DEF.finditer(text):
        name = m.group(1) or m.group(3)
        params = [p.strip() for p in (m.group(2) if m.group(1) else m.group(4) or "").split(",") if p.strip()]
        i, depth = m.end() - 1, 0
        j = i
        while j < len(text):
            c = text[j]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        out.append((name, params, text[i:j + 1]))
    return out


def _split_args(s: str) -> list[str]:
    args, depth, cur = [], 0, ""
    for c in s:
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
        if c == "," and depth == 0:
            args.append(cur.strip())
            cur = ""
        else:
            cur += c
    if cur.strip():
        args.append(cur.strip())
    return args


def _call_args(body: str, callee: str) -> list[list[str]]:
    out = []
    for m in re.finditer(callee + r"\s*\(", body):
        out.append(_split_args(body[m.end():]))
    return out


def guess_submission_callers(script_texts: list[str]) -> dict[str, int]:
    """WebSquare: 서브미션 id 를 매개변수로 받아 $p.executeSubmission 을 부르는 공통 래퍼 (예: gcm.executeSubmission)."""
    out: dict[str, int] = {}
    for t in script_texts:
        for name, params, body in _functions(t):
            idx = {p: i for i, p in enumerate(params)}
            for args in _call_args(body, r"executeSubmission"):
                if name and args and args[0] in idx:
                    out[name] = idx[args[0]]
                    break
    return out


def guess_wrappers(script_texts: list[str]) -> tuple[dict[str, dict], dict[str, dict]]:
    """프로젝트 공통 함수(gfn_ajax·gfn_transaction·gfn_popup 등) → frontApis / frontNavigations 초안."""
    apis: dict[str, dict] = {}
    navs: dict[str, dict] = {}
    fns = [f for t in script_texts for f in _functions(t)]
    for name, params, body in fns:
        if not name or not params or "." in name:
            continue
        idx = {p: i for i, p in enumerate(params)}
        # ---- 서버 요청
        m = re.search(r"\$\.ajax\s*\(\s*\{[^}]*?url\s*:\s*([A-Za-z_$][\w$]*)", body, re.S)
        if m and m.group(1) in idx:
            meth = re.search(r"(?:type|method)\s*:\s*['\"](\w+)['\"]", body)
            apis[name] = {"urlArg": idx[m.group(1)], "method": (meth.group(1) if meth else "GET").upper()}
            continue
        m = re.search(r"\$\.(get|post|getJSON)\s*\(\s*([A-Za-z_$][\w$]*)", body)
        if m and m.group(2) in idx:
            apis[name] = {"urlArg": idx[m.group(2)], "method": "POST" if m.group(1) == "post" else "GET"}
            continue
        m = re.search(r"location\.href\s*=\s*([A-Za-z_$][\w$]*)", body)
        if m and m.group(1) in idx:
            apis[name] = {"urlArg": idx[m.group(1)], "method": "GET"}
            continue
        for args in _call_args(body, r"(?<![\w.])(?:this\.)?transaction"):
            if len(args) >= 2 and args[1] in idx:
                spec = {"urlArg": idx[args[1]], "method": "POST"}
                if len(args) >= 6 and args[5] in idx:
                    spec["callbackArg"] = idx[args[5]]
                apis[name] = spec
                break
        if name in apis:
            continue
        # ---- 화면 이동·팝업
        url_params = [p for p in params if "url" in p.lower()]
        m = re.search(r"window\.open\s*\(\s*([A-Za-z_$][\w$]*)", body)
        if m and m.group(1) in idx:
            navs[name] = {"urlArg": idx[m.group(1)]}
            continue
        if url_params and re.search(r"showModal|ChildFrame|\bDialog\s*\(|open\w*\s*\(", body, re.I):
            spec = {"urlArg": idx[url_params[0]], "popup": bool(re.search(r"showModal|\bDialog\s*\(|ChildFrame", body))}
            cb = next((p for p in params if p.lower() in ("callback", "cb", "fn_callback", "callbackfn")), None)
            if cb and spec["popup"]:
                spec["callbackArg"] = idx[cb]
            navs[name] = spec
    # 한 단계 전파: 이미 찾은 요청 래퍼를 자기 매개변수 URL로 부르는 함수
    for _ in range(2):
        for name, params, body in fns:
            if not name or name in apis or name in navs:
                continue
            idx = {p: i for i, p in enumerate(params)}
            for w, spec in list(apis.items()):
                for args in _call_args(body, r"(?<![\w.])(?:this\.)?" + re.escape(w)):
                    if len(args) > spec["urlArg"] and args[spec["urlArg"]] in idx:
                        apis[name] = {"urlArg": idx[args[spec["urlArg"]]], "method": spec["method"]}
                        break
                if name in apis:
                    break
    return apis, navs


# ---------------------------------------------------------------- 감지


def detect(root: str | Path) -> Detection:
    root = Path(root).resolve()
    d = Detection(root)
    by_ext: dict[str, list[Path]] = defaultdict(list)
    n = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [x for x in dirnames if x not in SKIP_DIRS and not x.startswith(".")]
        for fn in filenames:
            p = Path(dirpath) / fn
            by_ext[p.suffix.lower()].append(p)
            n += 1
            if n >= MAX_FILES:
                d.notes.append(f"파일이 {MAX_FILES}개를 넘어 일부만 검사했습니다.")
                break
        if n >= MAX_FILES:
            break

    def rel(p: Path) -> str:
        try:
            return p.relative_to(root).as_posix()
        except ValueError:
            return p.as_posix()

    texts: dict[Path, str] = {}

    def text(p: Path) -> str:
        if p not in texts:
            texts[p] = _read(p)
        return texts[p]

    scripts: list[str] = []

    # ---------- JSP + jQuery
    jsps = by_ext[".jsp"]
    if jsps:
        webinf = [a for p in jsps for a in p.parents if a.name == "WEB-INF"]
        webapp = Counter(a.parent for a in webinf).most_common(1)[0][0] if webinf else _common_dir([p.parent for p in jsps])
        d.add("ui", "jsp-jquery", f"JSP {len(jsps)}개 (예: {rel(jsps[0])})")
        d.source["webapp"] = webapp
        d.source["jsp"] = webapp / "WEB-INF" / "jsp" if (webapp / "WEB-INF" / "jsp").is_dir() else webapp
        jq = [p for p in by_ext[".js"] if "jquery" in p.name.lower()]
        if jq:
            d.add("ui", "jsp-jquery", f"jQuery 라이브러리 {rel(jq[0])}")
        commons = sorted({"/" + rel(p.parent).split(rel(webapp) + "/", 1)[-1] + "/" for p in by_ext[".js"]
                          if p.parent.name.lower() in ("cmm", "common", "com", "comm") and webapp in p.parents})
        if commons:
            d.extra.setdefault("source", {})["commonScripts"] = commons
        scripts += [text(p) for p in by_ext[".js"] if webapp in p.parents and "jquery" not in p.name.lower()]

    # ---------- Nexacro / XPlatform (.xfdl), MiPlatform (<Window>), WebSquare (w2)
    nexa, xp = [], []
    for p in by_ext[".xfdl"]:
        head = text(p)[:3000]
        m = re.search(r"<FDL\s+version=\"(\d+)", head)
        # FDL 2.x = Nexacro N/17, FDL 1.x + <Layouts> = Nexacro 14, 그 외 FDL 1.x = XPlatform
        (nexa if (m and int(m.group(1)) >= 2) or "<Layouts>" in head else xp).append(p)
    if nexa and xp:
        # 같은 Nexacro 프로젝트 폴더 안의 FDL 1.x 는 Nexacro 14 형식으로 본다
        nroots = {p.parent for p in nexa}
        moved = [p for p in xp if p.parent in nroots]
        nexa += moved
        xp = [p for p in xp if p not in moved]
    mi, w2 = [], []
    for p in by_ext[".xml"]:
        head = text(p)[:3000]
        if re.search(r"<Window[\s>]", head) and "<Form" in head:
            mi.append(p)
        elif "xmlns:w2=" in head or "<w2:" in head:
            w2.append(p)
    if nexa:
        d.add("ui", "nexacro", f"Nexacro XFDL(FDL 2.x) {len(nexa)}개 (예: {rel(nexa[0])})")
        nroot = _common_dir([p.parent for p in nexa])
        typedef = [p for p in by_ext[".xml"] if p.name.lower() in ("typedefinition.xml",)]
        if typedef:
            nroot = typedef[0].parent
        elif nroot is not None and nroot.parent != root and nroot != root:
            nroot = nroot.parent
        d.source["nexacro"] = nroot
        services = {c.name: c.name + "/" for c in sorted(nroot.iterdir()) if c.is_dir()} if nroot and nroot.is_dir() else {}
        spec: dict = {"wireframe": True}
        if services and not typedef:
            spec["services"] = services
        xjs = by_ext[".xjs"]
        scripts += [text(p) for p in xjs]
        if any("svc::" in text(p) for p in [*xjs, *nexa]):
            spec["serviceUrls"] = {"svc": "/"}
        d.extra["nexacro"] = spec
        ds = [p for p in by_ext[".xml"] if "menu" in p.name.lower() and "<Dataset" in text(p)]
        if ds:
            d.menu = {"nexacroDataset": ds[0], "columns": _menu_columns(text(ds[0]), dataset=True)}
    if xp or mi:
        if xp:
            d.add("ui", "xplatform", f"XPlatform XFDL(FDL 1.x) {len(xp)}개 (예: {rel(xp[0])})")
        if mi:
            d.add("ui", "miplatform", f"MiPlatform <Window> 화면 XML {len(mi)}개 (예: {rel(mi[0])})")
        typedef = [p for p in by_ext[".xml"] if p.name.lower() == "default_typedef.xml"]
        d.source["xplatform"] = typedef[0].parent if typedef else _common_dir([p.parent for p in xp + mi])
        spec = {"wireframe": True}
        js = [p for p in by_ext[".xjs"] + by_ext[".js"] if d.source["xplatform"] in p.parents]
        scripts += [text(p) for p in js]
        if any("svc::" in text(p) for p in js + xp + mi):
            spec["serviceUrls"] = {"svc": "/"}
        d.extra["xplatform"] = spec
        d.front_navs["Dialog"] = {"urlArg": 0, "popup": True}
    if w2:
        d.add("ui", "websquare", f"WebSquare 화면 XML(w2) {len(w2)}개 (예: {rel(w2[0])})")
        first = rel(w2[0]).split("/", 1)[0]
        wroot = root / first if (root / first).is_dir() else _common_dir([p.parent for p in w2])
        d.source["websquare"] = wroot
        spec = {"urlPrefix": "/" + first, "wireframe": True}
        commons = [p for p in by_ext[".js"] if wroot in p.parents and p.parent.name.lower() == "js"
                   and not any(p.parent == x.parent for x in w2)]
        if commons:
            spec["commonScripts"] = [p.relative_to(wroot).as_posix() for p in commons]
            callers = guess_submission_callers([text(p) for p in commons])
            if callers:
                spec["submissionCallers"] = callers
        d.extra["websquare"] = spec

    # ---------- Vue / React
    vues = by_ext[".vue"]
    if vues:
        d.add("ui", "vue", f"Vue SFC {len(vues)}개 (예: {rel(vues[0])})")
        pkg = next((a for p in vues for a in p.parents if (a / "package.json").is_file()
                    and '"vue"' in text(a / "package.json")), None)
        vroot = pkg or _common_dir([p.parent for p in vues])
        d.source["vue"] = vroot
        spa = d.extra.setdefault("spa", {})
        router = [p for p in by_ext[".js"] + by_ext[".ts"] if vroot in p.parents
                  and re.search(r"createRouter|new\s+VueRouter", text(p))]
        if router:
            spa["router"] = router[0]
        if (vroot / "src").is_dir():
            spa["alias"] = {"@": "src"}
        d.menu = d.menu or {"router": True}
    reacts = [p for p in by_ext[".jsx"] + by_ext[".tsx"] if re.search(r"from\s+['\"]react['\"]", text(p))]
    if reacts:
        d.add("ui", "react", f"React 컴포넌트 {len(reacts)}개 (예: {rel(reacts[0])})")
        rroot = next((a for p in reacts for a in p.parents if (a / "package.json").is_file()), None)
        if rroot is None:
            top = rel(reacts[0]).split("/", 1)[0]
            rroot = root / top if (root / top).is_dir() else _common_dir([p.parent for p in reacts])
        d.source["react"] = rroot
        rr = [p for p in reacts if re.search(r"<Route[\s>]|createBrowserRouter", text(p))]
        if rr:
            d.extra.setdefault("spa", {})["reactRouter"] = rr[0]

    # ---------- .NET (WinForms · ASP.NET)
    cs = [p for p in by_ext[".cs"]]
    designers = [p for p in cs if p.name.endswith(".Designer.cs") and "System.Windows.Forms" in text(p)]
    aspx = [p for p in by_ext[".aspx"] if "<%@" in text(p)[:2000]]
    cshtml = by_ext[".cshtml"]
    if designers:
        d.add("ui", "winforms", f"WinForms 디자이너 {len(designers)}개 (예: {rel(designers[0])})")
        csproj = [p for p in by_ext[".csproj"] if "WinForms" in text(p) or "Windows.Forms" in text(p)]
        if csproj:
            exe_name = re.search(r"<AssemblyName>([^<]+)", text(csproj[0]))
            exe = (exe_name.group(1) if exe_name else csproj[0].stem) + ".exe"
            found = sorted((csproj[0].parent / "bin").rglob(exe)) if (csproj[0].parent / "bin").is_dir() else []
            d.extra["desktop"] = {"exe": found[-1] if found else csproj[0].parent / "bin" / "Release" / exe,
                                  "built": bool(found)}
        d.extra["winforms"] = {"wireframe": True}
    if aspx:
        d.add("ui", "aspnet-webforms", f"ASP.NET WebForms 페이지 {len(aspx)}개 (예: {rel(aspx[0])})")
        markers = ("web.config", "Global.asax")
        proj = next((a for p in aspx for a in p.parents if any((a / mk).is_file() for mk in markers)
                     or any(a.glob("*.csproj"))), None)
        if proj is None or proj == root:
            shallow = min((p.parent for p in aspx), key=lambda x: len(x.parts))
            proj = shallow.parent if shallow != root else shallow
        d.source["webforms"] = proj
    views = [p for p in cshtml if "Views" in p.parts]
    if views:
        mroot = next(a for a in views[0].parents if a.name == "Views").parent
        if (mroot / "Controllers").is_dir():
            d.add("ui", "aspnet-mvc", f"ASP.NET MVC 뷰 {len(views)}개 + Controllers (예: {rel(views[0])})")
            d.source["mvc"] = mroot
    if cs:
        data = [p for p in cs if re.search(r"SqlCommand|\.Query<|\.Execute\(|DbContext|DbSet<", text(p))]
        if data or designers or aspx or views:
            d.add("server", "dotnet", f"C# {len(cs)}개" + (f", 데이터 접근 {len(data)}개 (예: {rel(data[0])})" if data else ""))
            d.source["dotnet"] = _common_dir([p.parent for p in cs])
            mocks = [rel(p) for p in cs if re.search(r"(Mock|Stub|Fake)\w*\.cs$", p.name)]
            if mocks:
                d.extra.setdefault("dotnet", {})["exclude"] = ["*/" + m.rsplit("/", 1)[-1] for m in mocks]

    # ---------- Java · Spring · MyBatis/iBatis
    javas = by_ext[".java"]
    ctrl = [p for p in javas if re.search(r"@(Rest)?Controller\b|@RequestMapping", text(p))]
    if ctrl:
        d.add("server", "spring", f"Spring 컨트롤러 {len(ctrl)}개 (예: {rel(ctrl[0])})")
        src_main = next((a for a in ctrl[0].parents if a.name == "java" and a.parent.name == "main"), None)
        d.source["java"] = src_main or _common_dir([p.parent for p in javas])
    mappers = [p for p in by_ext[".xml"] if re.search(r"<mapper\s+namespace=|<sqlMap[\s>]", text(p)[:4000])]
    if mappers:
        d.add("server", "mybatis", f"MyBatis/iBatis 매퍼 {len(mappers)}개 (예: {rel(mappers[0])})")
        md = _common_dir([p.parent for p in mappers])
        top = next((a for a in [md, *md.parents] if a.name == "mapper"), md) if md else md
        d.source["mapper"] = top

    # ---------- Struts 1 (+ 자체 프레임워크 디스패치) · 쿼리 XML
    struts_files = [p for p in by_ext[".xml"] if re.match(r"struts.*\.xml$", p.name, re.I)
                    and "<action-mappings" in text(p)[:200000]]
    if struts_files:
        joined = "\n".join(text(p) for p in struts_files)
        n_act = len(re.findall(r"<action\b", joined))
        d.add("server", "struts1", f"Struts 1 설정 {len(struts_files)}개 · action {n_act}개 (예: {rel(struts_files[0])})")
        sdir = _common_dir([p.parent for p in struts_files])
        st: dict = {"configs": [rel(sdir) + "/**/struts*.xml" if sdir else "**/struts*.xml"], "extension": ".do"}
        webxml = next((p for p in by_ext[".xml"] if p.name == "web.xml" and "ActionServlet" in text(p)), None)
        if webxml is not None:
            st["webXml"] = webxml
            d.source.setdefault("webapp", webxml.parent.parent)
            jspdir = webxml.parent / "jsp"
            if jspdir.is_dir():
                d.source["jsp"] = jspdir
        # 자체 디스패치 액션: org.apache 가 아닌 type 중 가장 많은 것 + 표준에 없는 속성(빈 id 로 쓰이는 command 등)
        types = Counter(re.findall(r"<action\b[^>]*\btype=\"([\w.$]+)\"", joined))
        std = {"path", "type", "name", "scope", "parameter", "validate", "input", "forward", "include", "attribute",
               "roles", "prefix", "suffix", "unknown", "className", "cancellable", "catalog", "command"}
        top = next((t for t, n in types.most_common(3) if not t.startswith("org.apache") and n >= max(1, n_act // 10)), None)
        if top:
            attrs = Counter(a for tag in re.findall(r"<action\b[^>]*" + re.escape(top) + r"[^>]*>", joined)
                            for a in re.findall(r"\s(\w+)=", tag))
            svc_attr = "command" if attrs.get("command") else next((a for a, _ in attrs.most_common() if a not in std), None)
            if svc_attr:
                st["workerActions"] = [{"type": top, "serviceAttr": svc_attr, "methodPrefix": "do"}]
                d.notes.append(f"자체 디스패치 액션 {top} (빈 속성 '{svc_attr}') — methodPrefix(do) 를 서비스 소스로 확인하세요.")
        params = Counter(re.findall(r"<action\b[^>]*\bparameter=\"(\w+)\"", joined))
        if params:
            d.extra.setdefault("source", {})["keepQueryParams"] = [params.most_common(1)[0][0]]
        beans = [p for p in by_ext[".xml"] if "<bean " in text(p)[:200000] and re.search(r"<bean\s+id=\"\w+Service\"", text(p))]
        if beans:
            bdir = _common_dir([p.parent for p in beans])
            st["beanConfigs"] = [rel(bdir) + "/*.xml" if bdir else "**/*.xml"]
        d.extra["struts"] = st
        d.extra.setdefault("source", {})["jspIncludes"] = True
        srcjava = next((a for p in by_ext[".java"] for a in p.parents if a.name == "java" and a.parent.name == "src"), None)
        if srcjava is not None:
            d.source.setdefault("java", srcjava)
    queries = [p for p in by_ext[".xml"] if re.search(r"<query>\s*<id>", text(p)[:20000])]
    if queries:
        qdir = _common_dir([p.parent for p in queries])
        d.add("server", "queryxml", f"쿼리 XML(<query><id>…<value>) {len(queries)}개 (예: {rel(queries[0])})")
        d.extra["queryXml"] = {"files": [rel(qdir) + "/*.xml" if qdir else "**/*.xml"], "item": "query", "id": "id",
                               "sql": "value"}     # 동적 블록 $1{…} 은 기본값으로 처리

    # ---------- SQL (DDL·프로시저) → DB 종류
    sqls = by_ext[".sql"]
    ddl = [p for p in sqls if re.search(r"CREATE\s+(TABLE|(OR\s+REPLACE\s+)?(PROCEDURE|FUNCTION|PACKAGE))", text(p), re.I)]
    d.ddl_files = ddl
    score = Counter()
    for p in ddl:
        t = text(p)
        score["oracle"] += len(re.findall(r"VARCHAR2|\bNUMBER\s*\(|SYSDATE|\bNVL\s*\(|\bCLOB\b", t, re.I))
        score["mssql"] += len(re.findall(r"NVARCHAR|^\s*GO\s*$|IDENTITY\s*\(|GETDATE\s*\(|\bdbo\.", t, re.I | re.M))
        score["postgresql"] += len(re.findall(r"\bSERIAL\b|BIGSERIAL|TIMESTAMPTZ|::\w+", t, re.I))
        score["mysql"] += len(re.findall(r"AUTO_INCREMENT|ENGINE\s*=", t, re.I))
    if score and score.most_common(1)[0][1] > 0:
        d.db_type = score.most_common(1)[0][0]
        d.db_evidence = [f"{k} 키워드 {v}회" for k, v in score.most_common() if v]

    # ---------- 메뉴 CSV
    if not d.menu:
        for p in by_ext[".csv"]:
            head = text(p).splitlines()[0] if text(p) else ""
            if "MENU" in head.upper():
                d.menu = {"csv": p, "columns": _menu_columns(head)}
                break
    if not d.menu and designers:
        d.notes.append("메뉴: WinForms 는 메인폼 MenuStrip 에서 자동으로 만듭니다.")

    # ---------- 공통 래퍼
    apis, navs = guess_wrappers(scripts)
    d.front_apis.update(apis)
    for k, v in navs.items():
        d.front_navs.setdefault(k, v)
    return d


def _menu_columns(text_or_head: str, dataset: bool = False) -> dict[str, str]:
    if dataset:
        cols = re.findall(r"<Column\s+id=\"(\w+)\"", text_or_head)
    else:
        cols = [c.strip().strip('"') for c in text_or_head.split(",")]
    up = {c.upper(): c for c in cols}

    def pick(*cands):
        for want in cands:
            for u, c in up.items():
                if want in u:
                    return c
        return None
    out = {"id": pick("MENU_ID", "MENUID", "_ID", "ID"), "parent": pick("UP_", "PARENT", "PRNT", "HIGH"),
           "name": pick("MENU_NM", "NAME", "_NM", "NM"), "url": pick("URL", "PATH", "FORM"),
           "order": pick("SORT", "ORD", "SEQ")}
    if out["id"] == out["parent"]:
        out["id"] = pick("MENU_ID", "MENUID")
    return {k: v for k, v in out.items() if v}


# ---------------------------------------------------------------- YAML 생성


def _key(k) -> str:
    k = str(k)
    return k if re.fullmatch(r"[A-Za-z_][\w.]*", k) else '"' + k + '"'


def _q(v) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, dict):
        return "{ " + ", ".join(f"{_key(k)}: {_q(x)}" for k, x in v.items()) + " }"
    if isinstance(v, (list, tuple)):
        return "[" + ", ".join(_q(x) for x in v) + "]"
    s = str(v)
    if re.fullmatch(r"[A-Za-z0-9_./\-]+", s) and s not in ("true", "false", "null", "yes", "no", "on", "off"):
        return s
    return '"' + s.replace("\\", "/").replace('"', '\\"') + '"'


def render_yaml(d: Detection, out_path: Path, code: str, name: str) -> str:
    base = out_path.parent.resolve()

    def r(p: Path) -> str:
        """설정 파일 기준 상대 경로. 설정 파일이 소스 밖에 있어 '..' 로 올라가야 하면 절대 경로."""
        try:
            rel = Path(os.path.relpath(p, base)).as_posix()
        except ValueError:
            return Path(p).resolve().as_posix()
        return Path(p).resolve().as_posix() if rel.startswith("..") else rel

    L: list[str] = ["# PMS-X 수집기 설정 — `pmsx-collector init` 이 생성한 초안. 확인 후 고쳐서 쓰세요.",
                    f"# 소스 루트: {d.root.as_posix()}", "# 상대 경로는 이 파일 위치 기준입니다.", ""]
    ui, server = list(d.ui), [t for t in d.server if t != "queryxml"]
    L += ["system:", f"  code: {_q(code)}                        # TODO 시스템 코드 (서버에 등록한 시스템 코드와 같게)",
          f"  name: {_q(name)}", f"  uiTech: {_q(ui)}", f"  serverTech: {_q(server)}"]
    if d.db_type:
        L.append(f"  dbType: {d.db_type}                   # 근거: {', '.join(d.db_evidence)}")
    L += ["", "# 감지 근거"]
    for kind, bucket in (("화면", d.ui), ("서버", d.server)):
        for tech, why in bucket.items():
            L.append(f"#   [{kind}] {tech}: {' / '.join(why)}")
    for n in d.notes:
        L.append(f"#   참고: {n}")
    if not ui:
        L.append("#   ⚠ 화면 기술을 찾지 못했습니다. system.uiTech 를 직접 지정하세요.")
    L += ["", "source:", f"  root: {_q(r(d.root))}"]
    order = ["webapp", "jsp", "nexacro", "xplatform", "websquare", "vue", "react", "webforms", "mvc", "dotnet", "java", "mapper"]
    for k in order:
        if k in d.source and d.source[k] is not None:
            L.append(f"  {k}: {_q(r(d.source[k]))}")
    for k, v in (d.extra.get("source") or {}).items():
        L.append(f"  {k}: {_q(v)}")
    L.append("  includeSnippets: true                 # 고객사 정책상 소스 반입 불가면 false")
    for sec in ("nexacro", "xplatform", "websquare", "winforms", "dotnet", "struts", "queryXml"):
        if d.extra.get(sec):
            L += ["", f"{sec}:"] + [f"  {k}: {_q(r(v) if isinstance(v, Path) else v)}" for k, v in d.extra[sec].items()]
    if d.extra.get("spa"):
        L += ["", "spa:"]
        for k, v in d.extra["spa"].items():
            L.append(f"  {k}: {_q(r(v) if isinstance(v, Path) else v)}")
    if d.front_apis or d.front_navs:
        L += ["", "# 공통 함수 → 요청/화면 이동 해석 규칙 (소스에서 추정 — 반드시 확인)"]
        if d.front_apis:
            L.append("frontApis:")
            L += [f"  {k}: {_q(v)}" for k, v in sorted(d.front_apis.items())]
        if d.front_navs:
            L.append("frontNavigations:")
            L += [f"  {k}: {_q(v)}" for k, v in sorted(d.front_navs.items())]
    L += ["", "db:", f"  schema: {_q(code)}"]
    if d.db_type == "mssql":
        L.append("  dialect: tsql")
    if d.ddl_files:
        L.append(f"  ddlFiles: {_q([r(p) for p in d.ddl_files])}   # DB 접속 없이 DDL로 메타 수집")
    else:
        L.append("  # ddlFiles: [db/ddl.sql]               # DDL 파일이 없으면 아래 connection 으로 직접 수집")
    L += ["  # type: oracle                          # DB 직접 수집(읽기 전용 계정) — connection 이 있으면 사용",
          "  # connection: { host: 10.0.0.5, port: 1521, service: DEV, user: PMSX_RO, passwordEnv: PMSX_DB_PW }"]
    if d.menu:
        L += ["", "menu:"]
        for k, v in d.menu.items():
            L.append(f"  {k}: {_q(r(v) if isinstance(v, Path) else v)}")
    L += ["", "capture:", "  enabled: false                        # TODO 테스트계 URL·계정·denyHosts 확인 후 true"]
    if "jsp-jquery" in ui and not d.extra.get("desktop"):
        L += ["  # 테스트 서버가 없으면: JSP 를 HTML 로 바꿔 로컬 브라우저로 그리는 정적 렌더링 캡처 (서버 접속 없음)",
              "  # mode: static-render",
              "# render:                              # static-render 설정 (정적 리소스는 source.scriptRoots + webapp 에서 찾음)",
              "#   parallel: 6                         # 동시 렌더 화면 수",
              "#   timeoutSec: 10                      # 화면당 시간 제한",
              "#   imageFormat: jpeg                   # jpeg(용량 작음) | png",
              "#   branchPolicy: rich                  # if/else 분기 중 그릴 쪽: rich(내용 많은 쪽) | last(else) | first(if)",
              "#   screens: []                         # 일부 화면만 (화면 ID 목록) — 미리보기·시험용"]
    if d.extra.get("desktop"):
        dk = d.extra["desktop"]
        L += ["  desktop:", f"    exe: {_q(r(dk['exe']))}" + ("" if dk["built"] else "   # 아직 빌드 결과 없음 — 빌드 후 경로 확인"),
              "    startTimeoutSec: 20"]
    elif any(t in ui for t in ("jsp-jquery", "vue", "react", "aspnet-webforms", "aspnet-mvc", "websquare")):
        L += ["  baseUrl: http://테스트계-주소           # TODO", "  denyHosts: []                         # TODO 운영계 호스트 (일치하면 실행 거부)",
              "  browserChannel: chrome", "  headless: true", "  viewport: { width: 1280, height: 800 }",
              "  # login: { url: /login.do, steps: [ { fill: { selector: \"#userId\", valueEnv: PMSX_TEST_ID } }, "
              "{ fill: { selector: \"#userPw\", valueEnv: PMSX_TEST_PW } }, { click: \"#btnLogin\" } ] }",
              "  allowRequests: []                     # 조회용 POST 패턴 (예: \"*/list.do\") — 그 외 변경성 요청은 차단"]
    L += ["", "inference:", "  minEvidence: 3                        # 추정 관계 최소 근거 SQL 수", "", "output: out", ""]
    return "\n".join(L)


def init_config(root: str | Path, out: str | Path | None = None, code: str | None = None, name: str | None = None,
                force: bool = False) -> tuple[Path, Detection]:
    root = Path(root).resolve()
    out_path = Path(out).resolve() if out else root / "collector.yaml"
    if out_path.exists() and not force:
        raise FileExistsError(f"{out_path} 가 이미 있습니다. --force 로 덮어쓰거나 -o 로 다른 경로를 지정하세요.")
    d = detect(root)
    code = code or re.sub(r"[^A-Z0-9]", "", root.name.upper())[:8] or "SYS"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(render_yaml(d, out_path, code, name or root.name), encoding="utf-8")
    return out_path, d
