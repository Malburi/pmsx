"""CLI — 현장 투입 순서: init → doctor → run → upload

  python -m pmsx_collector init <소스 루트> [-o collector.yaml] [--code HR] [--name 인사시스템] [--force]
  python -m pmsx_collector doctor -c collector.yaml [--skip browser,network,db]
  python -m pmsx_collector run -c collector.yaml [--no-capture] [--incremental]
  python -m pmsx_collector upload -c collector.yaml --server https://pmsx.local --user analyst [--package x.zip]
  python -m pmsx_collector validate <model.json>
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .config import load_config
from .validate import validate_file


def _utf8_stdout():
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass


def _cmd_init(a) -> int:
    from .scaffold import init_config
    try:
        out, d = init_config(a.root, a.output, a.code, a.name, a.force)
    except FileExistsError as e:
        print("✗", e)
        return 2
    print(f"[pmsx-collector] 설정 초안 생성: {out}")
    print(f"  화면 기술 {', '.join(d.ui) or '(없음)'} · 서버 기술 {', '.join(d.server) or '(없음)'} · DB {d.db_type or '(미상)'}")
    for kind, bucket in (("화면", d.ui), ("서버", d.server)):
        for tech, why in bucket.items():
            print(f"    [{kind}] {tech}: {why[0]}")
    if d.front_apis or d.front_navs:
        print(f"  공통 함수 추정: 요청 {', '.join(d.front_apis) or '-'} · 화면 이동 {', '.join(d.front_navs) or '-'}")
    print("  다음 단계: 파일의 TODO(시스템 코드·캡처 설정)를 확인한 뒤 doctor → run")
    return 0 if d.ui else 1


def _cmd_doctor(a) -> int:
    from .doctor import FAIL, format_table, run_doctor
    try:
        cfg = load_config(a.config)
    except (OSError, ValueError) as e:
        print(f"✗ FAIL 설정 파일  {e}")
        return 1
    skip = {s.strip() for s in (a.skip or "").split(",") if s.strip()}
    checks = run_doctor(cfg, browser_timeout=a.timeout * 6, net_timeout=a.timeout, db_timeout=a.timeout + 3, skip=skip)
    print(f"[pmsx-collector] 실행 환경 점검 — {cfg.get('system.name')} ({cfg.sys_code})")
    print(format_table(checks))
    return 1 if any(c.status == FAIL for c in checks) else 0


def _cmd_upload(a) -> int:
    from .upload import UploadError, latest_package, upload
    try:
        if a.package:
            pkg, code = Path(a.package), a.system
            if not code:
                raise UploadError("--package 를 쓸 때는 --system 으로 시스템 코드를 지정하세요.")
        else:
            if not a.config:
                raise UploadError("-c collector.yaml 또는 --package 가 필요합니다.")
            cfg = load_config(a.config)
            code = a.system or cfg.sys_code
            pkg = latest_package(cfg.resolve(cfg.get("output", "out")), cfg.sys_code)
        print(f"[pmsx-collector] 업로드: {pkg.name} → {a.server} (시스템 {code})")
        res = upload(a.server, code, a.user, pkg, a.password_env, a.project, a.ca_file, a.timeout)
    except UploadError as e:
        print("✗", e)
        return 1
    run = res["run"] or {}
    diff = run.get("diff") or {}
    print(f"  프로젝트 {res['project'].get('name')} · 시스템 {res['system'].get('name')} · 회차 {run.get('seq')} ({run.get('runId')})")
    if diff:
        print(f"  변경: 화면 +{diff.get('screensAdded', 0)}/-{diff.get('screensRemoved', 0)} · 요소 +{diff.get('elementsAdded', 0)}"
              f"/-{diff.get('elementsRemoved', 0)} · 연결선 +{diff.get('edgesAdded', 0)}/-{diff.get('edgesRemoved', 0)}")
    return 0


def _cmd_run(a) -> int:
    from .pipeline import run   # 무거운 의존성은 run 에서만

    cfg = load_config(a.config)
    print(f"[pmsx-collector] {cfg.get('system.name')} ({cfg.sys_code}) 수집 시작" + (" — 증분" if a.incremental else ""))
    res = run(cfg, capture=not a.no_capture, incremental=a.incremental)
    st = res.model["manifest"]["stats"]
    print(f"[pmsx-collector] 회차 {res.run_id} · {st.get('durationSec')}초")
    print(f"  화면 {st['screens']} (캡처 {st['screensCaptured']} · 와이어프레임 {st.get('screensWireframe', 0)}) · 요소 {st['elements']} (매칭률 {st['elementMatchRate'] * 100:.0f}%)"
          f" · 버튼→핸들러 {st['buttonsWithHandler']}/{st['buttons']} · 연결선 {st['edges']} (동적 {st['edgesDynamic']})"
          f" · 차단 요청 {st['blockedRequests']} · 추정 관계 {st['relationsInferred']} · 실패 {st['failures']} · 경고 {st.get('warnings', 0)}")
    if a.incremental and "incremental" in st:
        print(f"  증분: 변경 파일 {st.get('incrementalChangedFiles', 0)} · 다시 캡처 {st.get('incrementalScreensRecaptured', 0)}화면"
              f" · 캡처 재사용 {st.get('incrementalScreensReused', 0)}화면")
    print(f"  패키지: {res.zip_path}")
    if res.errors:
        print(f"✗ 스키마 위반 {len(res.errors)}건:")
        for e in res.errors[:20]:
            print("   ", e)
        return 2
    print("  스키마 검증 통과")
    return 0


def main(argv: list[str] | None = None) -> int:
    _utf8_stdout()
    ap = argparse.ArgumentParser(prog="pmsx_collector", description="PMS-X As-Is 수집기 (init → doctor → run → upload)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    i = sub.add_parser("init", help="소스 루트를 훑어 화면·서버 기술을 감지하고 collector.yaml 초안 생성")
    i.add_argument("root", help="분석할 소스 루트 폴더")
    i.add_argument("-o", "--output", help="생성할 설정 파일 (기본: <root>/collector.yaml)")
    i.add_argument("--code", help="시스템 코드 (기본: 폴더 이름에서 추출)")
    i.add_argument("--name", help="시스템 이름")
    i.add_argument("--force", action="store_true", help="이미 있으면 덮어쓰기")

    d = sub.add_parser("doctor", help="실행 환경 점검 (브라우저·경로·DB·대상 URL·디스크·글꼴)")
    d.add_argument("-c", "--config", required=True)
    d.add_argument("--skip", help="건너뛸 점검: browser,network,db")
    d.add_argument("--timeout", type=float, default=5, help="네트워크·DB 점검 시간 제한(초)")

    r = sub.add_parser("run", help="정적 분석 + 동적 캡처 → 결과 패키지(zip)")
    r.add_argument("-c", "--config", required=True, help="collector.yaml 경로")
    r.add_argument("--no-capture", action="store_true", help="동적 캡처 생략 (정적 분석만)")
    r.add_argument("--incremental", action="store_true", help="이전 회차(out/.state.json)와 비교해 바뀐 화면만 다시 캡처")

    u = sub.add_parser("upload", help="결과 패키지를 PMS-X 서버에 업로드 (망 연결 시)")
    u.add_argument("-c", "--config", help="collector.yaml — 출력 폴더의 최신 패키지를 올린다")
    u.add_argument("--package", help="올릴 zip 직접 지정")
    u.add_argument("--server", required=True, help="서버 주소 (예: https://pmsx.example.local)")
    u.add_argument("--system", help="시스템 코드 (기본: 설정의 system.code)")
    u.add_argument("--user", required=True, help="로그인 아이디")
    u.add_argument("--password-env", default="PMSX_UPLOAD_PW", help="비밀번호를 담은 환경변수 이름")
    u.add_argument("--project", help="같은 시스템 코드가 여러 프로젝트에 있을 때 프로젝트 ID 또는 이름")
    u.add_argument("--ca-file", help="사내 인증서(CA) 파일 — HTTPS 서버 인증서 검증용")
    u.add_argument("--timeout", type=float, default=120)

    v = sub.add_parser("validate", help="model.json 스키마 검증")
    v.add_argument("model")
    a = ap.parse_args(argv)

    if a.cmd == "validate":
        errs = validate_file(a.model)
        for e in errs:
            print("✗", e)
        print("스키마 검증 통과" if not errs else f"스키마 위반 {len(errs)}건")
        return 0 if not errs else 2
    return {"init": _cmd_init, "doctor": _cmd_doctor, "upload": _cmd_upload, "run": _cmd_run}[a.cmd](a)
