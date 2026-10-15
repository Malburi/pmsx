"""안정 키(stable key) 규칙 — docs/04_수집기설계_중간모델.md §4.

재수집해도 같은 대상이면 같은 키가 나와야 한다. 서버는 (시스템, 키)로 upsert 한다.
"""


def sys_key(code: str) -> str:
    return f"sys:{code}"


def menu_key(sys_code: str, menu_id: str) -> str:
    return f"menu:{sys_code}:{menu_id}"


def scr_key(sys_code: str, screen_id: str) -> str:
    return f"scr:{sys_code}:{screen_id}"


def el_key(screen_key: str, element_id: str) -> str:
    # scr:HR:HR0101 + btnSave -> el:HR:HR0101#btnSave
    return f"el:{screen_key[len('scr:'):]}#{element_id}"


def fn_key(lang: str, file: str, name: str) -> str:
    return f"fn:{lang}:{file}#{name}"


def api_key(method: str, path: str) -> str:
    return f"api:{method.upper()} {path}"


def short_namespace(namespace: str) -> str:
    """kr.go.hr.emp.service.impl.EmpMapper -> EmpMapper"""
    return namespace.rsplit(".", 1)[-1]


def sql_key(ns_name: str, stmt_id: str) -> str:
    """ns_name 은 키에 쓸 네임스페이스 이름 — 보통 단축 이름(EmpMapper),
    같은 단축 이름의 Mapper 가 둘 이상이면 전체 네임스페이스(ctx.mapper_namespaces 가 결정)."""
    return f"sql:{ns_name}.{stmt_id}"


def sp_key(schema: str, name: str) -> str:
    return f"sp:{schema}.{name.upper()}"


def tbl_key(schema: str, name: str) -> str:
    return f"tbl:{schema}.{name.upper()}"
