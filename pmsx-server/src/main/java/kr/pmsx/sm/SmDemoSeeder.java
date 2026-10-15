package kr.pmsx.sm;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import kr.pmsx.as.analysis.AnalysisService;
import kr.pmsx.as.viewer.ViewerService;
import kr.pmsx.cm.DataSeeder;
import kr.pmsx.cm.UserDao;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.sm.HandoverController.HandoverRequest;
import kr.pmsx.sm.HandoverController.Include;
import kr.pmsx.sm.HandoverController.NewProject;
import kr.pmsx.sm.HandoverController.Target;
import kr.pmsx.sm.SlaEngine.Policy;
import kr.pmsx.sm.SrService.Requester;
import kr.pmsx.sm.SrService.SrRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * R3 데모 데이터 (pmsx.seed.demo=true, local 전용). SM 프로젝트가 하나도 없을 때 1회:
 * SI 프로젝트에 데모 수집 결과 적재(DemoAsIsLoader: HR·PY·ED) + 시연용 분석 상태·메모·핫스팟 결함 → SI→SM 인수인계 서비스로 SM 프로젝트 생성 →
 * SR 30건(SLA 정상·임박·위반·일시정지·충족 골고루), 장애 3, 변경 5, KB 8, 운영 자산 10, 정기 작업 4, 지난달 운영보고,
 * 고객 계정 customer / pmsx1234! (CUSTOMER, ○○공사).
 */
@Component
@Order(40)
public class SmDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SmDemoSeeder.class);

    private final JdbcTemplate jdbc;
    private final PmsxProperties props;
    private final UserDao users;
    private final PasswordEncoder encoder;
    private final DemoAsIsLoader asIs;
    private final ViewerService viewer;
    private final AnalysisService analysis;
    private final HandoverController handover;
    private final SrService srs;
    private final SlaService sla;
    private final IncidentController incidents;
    private final ChangeController changes;
    private final KbController kb;
    private final RoutineController routine;
    private final MonthlyReportController reports;

    public SmDemoSeeder(JdbcTemplate jdbc, PmsxProperties props, UserDao users, PasswordEncoder encoder,
            DemoAsIsLoader asIs, ViewerService viewer, AnalysisService analysis, HandoverController handover, SrService srs, SlaService sla,
            IncidentController incidents, ChangeController changes, KbController kb, RoutineController routine,
            MonthlyReportController reports) {
        this.jdbc = jdbc;
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.asIs = asIs;
        this.viewer = viewer;
        this.analysis = analysis;
        this.handover = handover;
        this.srs = srs;
        this.sla = sla;
        this.incidents = incidents;
        this.changes = changes;
        this.kb = kb;
        this.routine = routine;
        this.reports = reports;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!props.demoSeed()) {
            return;
        }
        Integer sm = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_PROJECT WHERE TYPE = 'SM'", Integer.class);
        if (sm != null && sm > 0) {
            return;
        }
        String siId = jdbc.query("SELECT PROJECT_ID FROM PJ_PROJECT WHERE NAME LIKE '%차세대%' ORDER BY CREATED_AT LIMIT 1",
                (rs, i) -> rs.getString(1)).stream().findFirst().orElse(null);
        String adminId = users.findByLoginId("admin").map(u -> u.userId()).orElse(null);
        if (siId == null || adminId == null) {
            return;
        }
        AuthUser admin = new AuthUser(adminId, "admin", "관리자", List.of("SYS_ADMIN"));
        String hrId = jdbc.query("SELECT SYSTEM_ID FROM AS_SYSTEM WHERE PROJECT_ID = ? AND CODE = 'HR'",
                (rs, i) -> rs.getString(1), siId).stream().findFirst().orElse(null);
        if (hrId == null) {
            return;
        }
        // SI 프로젝트에 먼저 수집 결과 적재(HR·PY·ED) → 시연용 분석 상태·메모·핫스팟 결함 → 그 다음 HR 을 SM 으로 인수인계
        asIs.load(admin, siId);
        enrichAsIs(admin, siId, hrId);

        // ── 고객 계정 ──
        String hash = encoder.encode(DataSeeder.SEED_PASSWORD);
        String customerId = users.findByLoginId("customer").map(u -> u.userId())
                .orElseGet(() -> users.insert("customer", hash, "김고객", List.of()));
        jdbc.update("UPDATE CM_USER SET ORG_TYPE = 'CUSTOMER', COMPANY = '○○공사', EMAIL = 'customer@example.local'"
                + " WHERE USER_ID = ?", customerId);
        String opId = users.findByLoginId("op.seo").map(u -> u.userId()).orElse(null);
        String plId = users.findByLoginId("pl.kim").map(u -> u.userId()).orElse(null);
        String custOh = users.findByLoginId("cust.oh").map(u -> u.userId()).orElse(null);

        // ── SI→SM 인수인계 (실제 서비스 사용) ──
        LocalDate today = LocalDate.now(Times.ZONE);
        List<String> members = new ArrayList<>();
        for (String u : new String[] {opId, plId, custOh}) {
            if (u != null) {
                members.add(u);
            }
        }
        var result = handover.run(admin, siId, new HandoverRequest(new Target(new NewProject("○○공사 인사시스템 운영(SM)",
                "○○공사", today.minusMonths(3).withDayOfMonth(1), today.plusMonths(9).withDayOfMonth(1).minusDays(1),
                adminId), null), List.of(hrId), members, new Include(true, true, false, true)));
        String pid = result.smProjectId();
        if (opId == null) {
            opId = adminId;
        } else {
            jdbc.update("UPDATE PJ_MEMBER SET ROLE_ID = 'OPERATOR' WHERE PROJECT_ID = ? AND USER_ID = ?", pid, opId);
        }
        jdbc.update("INSERT INTO PJ_MEMBER (PROJECT_ID, USER_ID, ROLE_ID, IN_DT) VALUES (?, ?, 'CUSTOMER', ?)", pid,
                customerId, today.minusMonths(3));
        String smHr = jdbc.queryForObject("SELECT SYSTEM_ID FROM AS_SYSTEM WHERE PROJECT_ID = ? AND CODE = 'HR'",
                String.class, pid);
        List<String[]> screens = jdbc.query("SELECT SCREEN_ID, NAME FROM AS_SCREEN WHERE SYSTEM_ID = ? ORDER BY SCREEN_CODE",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, smHr);
        String saveBtn = jdbc.query("""
                SELECT E.ELEMENT_ID FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID
                 WHERE E.SYSTEM_ID = ? AND E.ELEMENT_CODE = 'btnSave' ORDER BY S.SCREEN_CODE""",
                (rs, i) -> rs.getString(1), smHr).stream().findFirst().orElse(null);

        seedSrs(pid, admin, opId, customerId, screens, saveBtn);
        seedOps(pid, admin, opId, smHr, screens);
        try {
            reports.generate(admin, pid, new MonthlyReportController.GenerateRequest(YearMonth.from(today).minusMonths(1)
                    .toString()));
        } catch (RuntimeException e) {
            log.warn("데모 월간 운영보고 생성 실패: {}", e.getMessage());
        }
        log.info("R3 데모 데이터: SM 프로젝트 '○○공사 인사시스템 운영(SM)' (인수인계 {}), 고객 계정 customer / {}",
                result.copied(), DataSeeder.SEED_PASSWORD);
    }

    /** 시연용: 분석 상태(확정·검토·작업), 화면·버튼 메모, 핫스팟 결함·추적 링크 */
    private void enrichAsIs(AuthUser admin, String siId, String hrId) {
        try {
            List<String[]> screens = jdbc.query("""
                    SELECT SCREEN_ID, SCREEN_CODE FROM AS_SCREEN WHERE SYSTEM_ID = ? AND KIND <> 'popup'
                     ORDER BY SCREEN_CODE""", (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, hrId);
            if (screens.isEmpty()) {
                return;
            }
            String analystId = users.findByLoginId("analyst").map(u -> u.userId()).orElse(null);
            String[] plan = {"DONE", "DONE", "REVIEW", "WORKING"};
            for (int i = 0; i < Math.min(plan.length, screens.size()); i++) {
                java.util.Map<String, Object> body = new java.util.HashMap<>();
                body.put("ownerId", analystId);
                if ("DONE".equals(plan[i])) {
                    body.put("status", "REVIEW");
                    body.put("comment", "분석 완료, 검토 요청");
                    viewer.patchAnalysis(admin, screens.get(i)[0], body);
                    body = new java.util.HashMap<>();
                    body.put("status", "DONE");
                    body.put("reviewerId", admin.userId());
                    body.put("comment", "검토 완료 — 확정");
                } else {
                    body.put("status", plan[i]);
                    body.put("comment", "REVIEW".equals(plan[i]) ? "검토 요청" : "분석 진행 중");
                }
                viewer.patchAnalysis(admin, screens.get(i)[0], body);
            }
            String hr0101 = screens.stream().filter(x -> "HR0101".equals(x[1])).map(x -> x[0]).findFirst()
                    .orElse(screens.get(0)[0]);
            String saveBtn = jdbc.query("SELECT ELEMENT_ID FROM AS_ELEMENT WHERE SCREEN_ID = ? AND ELEMENT_CODE = 'btnSave'",
                    (rs, i) -> rs.getString(1), hr0101).stream().findFirst().orElse(null);
            analysis.createNote(admin, new AnalysisService.NoteRequest("SCREEN", hr0101, """
                    ## 업무 규칙
                    - 사원번호는 저장 시 `SEQ_EMP_NO` 로 자동 채번한다.
                    - 퇴사자(`USE_YN = 'N'`)는 기본 조회에서 제외한다.
                    - 부서 콤보는 사용 중인 부서만 노출한다."""));
            if (saveBtn != null) {
                analysis.createNote(admin, new AnalysisService.NoteRequest("ELEMENT", saveBtn,
                        "입사일이 미래 날짜여도 저장됨 — 업무 규칙상 오류 (결함 DEF-0001, To-Be 에서 검증 추가)"));
                jdbc.update("UPDATE QA_DEFECT SET SCREEN_ID = ?, ELEMENT_ID = ? WHERE PROJECT_ID = ? AND TITLE = ?",
                        hr0101, saveBtn, siId, "미래 입사일이 저장됨");
            }
            String reqId = jdbc.query("SELECT REQ_ID FROM DL_REQUIREMENT WHERE PROJECT_ID = ? ORDER BY REQ_NO",
                    (rs, i) -> rs.getString(1), siId).stream().findFirst().orElse(null);
            if (reqId != null) {
                jdbc.update("""
                        INSERT INTO DL_TRACE_LINK (LINK_ID, PROJECT_ID, FROM_TYPE, FROM_ID, TO_TYPE, TO_ID, LINK_TYPE,
                                                   CREATED_AT, CREATED_BY)
                        VALUES (?, ?, 'REQ', ?, 'SCREEN', ?, 'IMPLEMENTS', ?, ?)""",
                        kr.pmsx.common.Ulid.next(), siId, reqId, hr0101, Times.now(), admin.userId());
            }
        } catch (RuntimeException e) {
            log.warn("데모 As-Is 시연 데이터 생성 실패: {}", e.getMessage());
        }
    }

    // ───────────────────────── SR ─────────────────────────

    private record Spec(String type, String priority, String title, String desc, String state, boolean portal,
                        int screenIdx, boolean onSave) {
    }

    private void seedSrs(String pid, AuthUser admin, String opId, String customerId, List<String[]> screens,
            String saveBtn) {
        List<Spec> specs = List.of(
                new Spec("INQUIRY", "P3", "사원 조회 시 퇴사자가 함께 나옵니다", "재직자만 보이도록 조회 조건 확인 부탁드립니다.", "NEW", true, 0, false),
                new Spec("AUTH", "P3", "신규 입사자 3명 권한 신청", "인사팀 신규 입사자 사용자 권한 부여 요청", "NEW", true, -1, false),
                new Spec("DATA", "P4", "10월 발령 내역 엑셀 추출 요청", "감사 대응용 발령 내역 추출", "ASSIGNED", true, 2, false),
                new Spec("IMPROVE", "P4", "부서 콤보에 폐지 부서 숨김", "폐지 부서가 콤보에 계속 노출됩니다.", "ASSIGNED", false, 3, false),
                new Spec("INQUIRY", "P3", "사원 상세 팝업 연락처 마스킹 문의", "개인정보 마스킹 기준 문의", "NEW", true, 1, false),
                new Spec("DATA", "P3", "교육이력 일괄 등록 요청", "엑셀 120건 일괄 등록", "NEW", false, -1, false),
                new Spec("INQUIRY", "P2", "발령 등록 화면 응답이 느립니다", "오전부터 발령 조회가 10초 이상 걸립니다.", "RESP_SOON", true, 2, false),
                new Spec("AUTH", "P2", "인사팀장 결재 권한 누락", "결재 메뉴가 보이지 않습니다.", "RESP_SOON", true, -1, false),
                new Spec("IMPROVE", "P3", "사원 저장 시 미래 입사일 입력 가능", "입사일 검증 추가 요청", "RESP_SOON", false, 0, true),
                new Spec("IMPROVE", "P3", "사원 저장 후 목록 재조회 안 됨", "저장 후 건수가 갱신되지 않습니다.", "OK", true, 0, true),
                new Spec("DATA", "P3", "부서 코드 일괄 변경", "조직개편에 따른 부서 코드 변경", "OK", false, 3, false),
                new Spec("IMPROVE", "P4", "발령 목록 정렬 기준 변경", "발령일 내림차순 정렬 요청", "OK", false, 2, false),
                new Spec("INQUIRY", "P3", "사원 엑셀 다운로드 컬럼 문의", "직위 컬럼 추가 가능 여부", "OK", true, 0, false),
                new Spec("IMPROVE", "P2", "사원 저장 시 중복 사번 오류", "같은 사번이 두 번 저장됩니다.", "SOON", true, 0, true),
                new Spec("INCIDENT", "P2", "교육이력 화면 오류 발생", "조회 시 500 오류", "SOON", true, 4, false),
                new Spec("DATA", "P3", "휴직자 명단 추출", "분기 보고용", "SOON", false, -1, false),
                new Spec("INCIDENT", "P1", "인사시스템 로그인 불가", "09시부터 전 직원 로그인 실패", "BREACHED", true, -1, false),
                new Spec("IMPROVE", "P3", "부서 관리 화면 저장 버튼 비활성", "권한이 있는데 저장이 안 됩니다.", "BREACHED", true, 3, false),
                new Spec("DATA", "P4", "퇴직자 개인정보 파기 대상 추출", "보존 기간 경과자", "BREACHED", false, -1, false),
                new Spec("INQUIRY", "P3", "발령 취소 절차 문의", "잘못 등록한 발령 취소 방법", "BREACHED", true, 2, false),
                new Spec("IMPROVE", "P3", "사원 사진 업로드 용량 확대", "5MB → 10MB", "PAUSED", true, 1, false),
                new Spec("DATA", "P3", "급여 연동 데이터 재전송", "급여시스템 담당자 확인 대기", "PAUSED", false, -1, false),
                new Spec("INQUIRY", "P3", "비밀번호 초기화 요청", "로그인 5회 실패로 잠김", "MET", true, -1, false),
                new Spec("IMPROVE", "P3", "사원 저장 버튼 위치 변경", "하단 고정 요청", "MET", true, 0, true),
                new Spec("INCIDENT", "P2", "발령 조회 시 타임아웃", "인덱스 누락으로 지연", "MET", false, 2, false),
                new Spec("DATA", "P4", "9월 입사자 명단 추출", "인사위원회 보고용", "MET", true, -1, false),
                new Spec("AUTH", "P3", "감사팀 조회 권한 부여", "한시적 조회 권한", "MET", true, -1, false),
                new Spec("IMPROVE", "P4", "부서 콤보 정렬 순서", "가나다순 요청", "MET", false, 3, false),
                new Spec("INCIDENT", "P1", "인사 배치 야간 실패", "발령 반영 배치 실패, 재처리 완료", "LATE", false, -1, false),
                new Spec("INQUIRY", "P3", "중복 요청 건", "SR-0001 과 동일", "REJECTED", true, -1, false));
        OffsetDateTime now = Times.now();
        SlaService.Evaluator ev = sla.evaluator(now);
        Requester cust = new Requester("김고객", "○○공사", "customer@example.local", customerId);
        Requester internal = new Requester("박인사", "○○공사 인사팀", "02-000-0000", null);
        int n = 0;
        for (Spec s : specs) {
            n++;
            Policy p = sla.match(pid, s.type(), s.priority());
            SlaCalculator calc = ev.calculator(p);
            int resp = p.responseMinutes();
            int res = p.resolveMinutes();
            int soonRes = (int) Math.max(res * 0.2, 30);
            int soonResp = (int) Math.max(resp * 0.2, 30);
            OffsetDateTime created;
            switch (s.state()) {
                case "NEW", "ASSIGNED" -> created = calc.minus(now, Math.max(1, resp / 5));
                case "RESP_SOON" -> created = calc.minus(now, resp - soonResp / 2);
                case "OK" -> created = calc.minus(now, Math.max(resp + 10, (long) (res * 0.3)));
                case "SOON" -> created = calc.minus(now, res - soonRes / 2);
                case "BREACHED" -> created = calc.minus(now, res + 90);
                case "PAUSED" -> created = calc.minus(now, (long) (res * 0.5));
                default -> created = now.minusDays(5 + n).withHour(10).withMinute(0);  // MET·LATE·REJECTED
            }
            String screenId = s.screenIdx() >= 0 && s.screenIdx() < screens.size() ? screens.get(s.screenIdx())[0] : null;
            String elementId = s.onSave() ? saveBtn : null;
            if (elementId != null) {
                screenId = null;
            }
            var sr = srs.create(pid, new SrRequest(s.type(), s.priority(), s.title(), s.desc(),
                    s.portal() ? "PORTAL" : "MANUAL", null, null, null, screenId, elementId),
                    s.portal() ? "PORTAL" : "MANUAL", s.portal() ? cust : internal, admin, created, null);
            String id = sr.srId();
            OffsetDateTime responded = calc.plus(created, Math.max(1, resp / 3));
            String status;
            OffsetDateTime resolved = null;
            OffsetDateTime closed = null;
            String assignee = opId;
            switch (s.state()) {
                case "NEW" -> {
                    status = "RECEIVED";
                    responded = null;
                    assignee = null;
                }
                case "ASSIGNED", "RESP_SOON" -> {
                    status = "ASSIGNED";
                    responded = null;
                }
                case "OK", "SOON", "BREACHED" -> status = "IN_PROGRESS";
                case "PAUSED" -> {
                    status = "ON_HOLD";
                    sla.event(id, "PAUSE", calc.minus(now, 60));
                }
                case "MET" -> {
                    resolved = calc.plus(created, (long) (res * 0.5));
                    status = n % 2 == 0 ? "CLOSED" : "CONFIRMED";
                    closed = "CLOSED".equals(status) ? resolved.plusHours(4) : null;
                    sla.event(id, "STOP", resolved);
                }
                case "LATE" -> {
                    resolved = calc.plus(created, res + 120);
                    status = "CLOSED";
                    closed = resolved.plusHours(2);
                    sla.event(id, "STOP", resolved);
                }
                default -> {
                    status = "REJECTED";
                    responded = null;
                    closed = calc.plus(created, 30);
                    sla.event(id, "STOP", closed);
                }
            }
            if (responded != null && responded.isAfter(now)) {
                responded = now.minusMinutes(1);
            }
            jdbc.update("""
                    UPDATE SM_SR SET STATUS = ?, ASSIGNEE_ID = ?, RESPONDED_AT = ?, RESOLVED_AT = ?, CLOSED_AT = ?,
                           REJECT_REASON = ?, UPDATED_AT = ? WHERE SR_ID = ?""", status, assignee, responded, resolved,
                    closed, "REJECTED".equals(status) ? "중복 요청" : null,
                    resolved != null ? resolved : responded != null ? responded : created, id);
            jdbc.update("INSERT INTO CM_STATUS_HIST (HIST_ID, TARGET_TYPE, TARGET_ID, ACTION, FROM_STATUS, TO_STATUS,"
                    + " COMMENT_TXT, HIST_AT, USER_ID) VALUES (?, 'SR', ?, 'STATUS', 'RECEIVED', ?, NULL, ?, ?)",
                    kr.pmsx.common.Ulid.next(), id, status, responded != null ? responded : created, admin.userId());
            if (responded != null) {
                jdbc.update("INSERT INTO SM_SR_COMMENT (COMMENT_ID, SR_ID, CONTENT, INTERNAL, AUTHOR_ID, CREATED_AT)"
                        + " VALUES (?, ?, ?, FALSE, ?, ?)", kr.pmsx.common.Ulid.next(), id,
                        "접수했습니다. 확인 후 처리하겠습니다.", assignee == null ? admin.userId() : assignee, responded);
            }
            if ("PAUSED".equals(s.state())) {
                jdbc.update("INSERT INTO SM_SR_COMMENT (COMMENT_ID, SR_ID, CONTENT, INTERNAL, AUTHOR_ID, CREATED_AT)"
                        + " VALUES (?, ?, ?, TRUE, ?, ?)", kr.pmsx.common.Ulid.next(), id,
                        "고객 회신 대기 — 보류 처리 (내부 메모)", assignee, calc.minus(now, 60));
            }
        }
    }

    // ───────────────────────── 장애·변경·KB·자산·정기 작업 ─────────────────────────

    private void seedOps(String pid, AuthUser admin, String opId, String smHr, List<String[]> screens) {
        OffsetDateTime now = Times.now();
        List<String> sys = List.of(smHr);
        incidents.insertIncident(pid, new IncidentController.IncidentRequest("인사시스템 로그인 장애", 2, "OPEN",
                now.minusHours(2), now.minusHours(2).plusMinutes(5), null, "전 직원 로그인 불가(약 1,200명)", sys,
                "인증 서버 세션 저장소 용량 초과 추정", null, null, null, List.of(
                new IncidentController.TimelineItem(null, now.minusHours(2), "모니터링 경보: 로그인 실패율 90%"),
                new IncidentController.TimelineItem(null, now.minusMinutes(100), "운영 담당 확인, 고객 공지"),
                new IncidentController.TimelineItem(null, now.minusMinutes(60), "세션 저장소 정리 작업 중"))), admin);
        incidents.insertIncident(pid, new IncidentController.IncidentRequest("발령 반영 배치 실패", 3, "CLOSED",
                now.minusDays(20).withHour(2), now.minusDays(20).withHour(7), now.minusDays(20).withHour(9),
                "당일 발령 3건 미반영", sys, "선행 배치 지연", "선행 배치 종료 확인 없이 후행 배치 기동",
                "배치 의존성 체크 추가, 실패 시 문자 알림", null, List.of()), admin);
        incidents.insertIncident(pid, new IncidentController.IncidentRequest("사원 조회 응답 지연", 4, "CLOSED",
                now.minusDays(35).withHour(10), now.minusDays(35).withHour(10).plusMinutes(20),
                now.minusDays(35).withHour(13), "조회 10초 이상 지연", sys, "통계 정보 갱신 누락",
                "대량 이관 후 통계 미갱신", "이관 작업 체크리스트에 통계 갱신 추가", null, List.of()), admin);

        var c1 = changes.insert(pid, new ChangeController.ChangeRequest("입사일 검증 로직 추가", "NORMAL", "M",
                "사원 저장 시 미래 입사일 차단", "이전 버전 jar 재배포", now.plusDays(7), null,
                screens.isEmpty() ? null : List.of(screens.get(0)[0]), List.of("a1b2c3d 입사일 검증")), admin);
        var c2 = changes.insert(pid, new ChangeController.ChangeRequest("세션 저장소 증설", "EMERGENCY", "H",
                "Redis 메모리 2GB → 8GB", "설정 원복", now.plusHours(3), null, null, null), admin);
        var c3 = changes.insert(pid, new ChangeController.ChangeRequest("발령 배치 의존성 체크", "NORMAL", "M",
                "선행 배치 완료 플래그 확인", "배치 설정 원복", now.minusDays(10), null, null,
                List.of("9f8e7d6 배치 선행 체크")), admin);
        var c4 = changes.insert(pid, new ChangeController.ChangeRequest("부서 콤보 정렬 변경", "STANDARD", "L",
                "가나다순", "원복", now.minusDays(25), null, null, null), admin);
        var c5 = changes.insert(pid, new ChangeController.ChangeRequest("사원 엑셀 직위 컬럼 추가", "NORMAL", "L",
                "엑셀 다운로드 컬럼 추가", "이전 버전 재배포", now.minusDays(5), null, null, null), admin);
        jdbc.update("UPDATE SM_CHANGE SET STATUS = 'CAB_APPROVED' WHERE CHANGE_ID = ?", c1.changeId());
        jdbc.update("INSERT INTO SM_CHANGE_CAB (CHANGE_ID, USER_ID, DECISION, COMMENT_TXT, DECIDED_AT) VALUES (?, ?, 'APPROVE', ?, ?)",
                c1.changeId(), admin.userId(), "다음 주 정기 배포에 포함", now.minusHours(5));
        jdbc.update("UPDATE SM_CHANGE SET STATUS = 'CLOSED', DEPLOYED_AT = ? WHERE CHANGE_ID = ?", now.minusDays(10),
                c3.changeId());
        jdbc.update("UPDATE SM_CHANGE SET STATUS = 'CLOSED', DEPLOYED_AT = ? WHERE CHANGE_ID = ?", now.minusDays(25),
                c4.changeId());
        jdbc.update("UPDATE SM_CHANGE SET STATUS = 'ROLLED_BACK', DEPLOYED_AT = ? WHERE CHANGE_ID = ?", now.minusDays(5),
                c5.changeId());
        jdbc.update("UPDATE SM_CHANGE SET STATUS = 'REQUESTED' WHERE CHANGE_ID = ?", c2.changeId());

        String[][] kbs = {
                {"비밀번호 초기화 절차", "## 절차\n\n1. 사용자 관리에서 계정 검색\n2. [비밀번호 초기화] → 임시 비밀번호 전달\n3. 첫 로그인 시 변경 안내", "계정,비밀번호"},
                {"사원 저장 시 입사일 오류 대응", "## 증상\n\n미래 입사일이 저장됨\n\n## 조치\n\n화면 검증 + 서버 검증(EmpServiceImpl.saveEmp) 확인", "사원관리,저장"},
                {"엑셀 다운로드가 느릴 때", "## 원인\n\n조회 조건 없이 전체 다운로드\n\n## 조치\n\n기간 조건 안내, 10만 건 이상은 배치 추출", "엑셀,성능"},
                {"부서 코드 변경 시 확인 사항", "- TB_DEPT 사용여부 컬럼\n- 발령 이력 부서 코드 유지\n- 콤보 캐시 초기화", "부서,코드"},
                {"발령 반영 배치 재처리", "## 재처리\n\n1. 실패 로그 확인\n2. 선행 배치 완료 확인\n3. 재처리 화면에서 일자 지정 실행", "배치,발령"},
                {"권한 신청 처리 기준", "- 인사팀장 승인 메일 필수\n- 한시 권한은 종료일 설정", "권한"},
                {"인증서 갱신 절차", "## 대상\n\nSSL 인증서(*.example.local)\n\n## 순서\n\nCSR 생성 → 발급 → WAS 교체 → 재기동", "인증서,운영자산"},
                {"로그인 장애 1차 점검", "1. 인증 서버 상태\n2. 세션 저장소 용량\n3. DB 커넥션 풀", "장애,로그인"}};
        for (String[] k : kbs) {
            kb.insert(pid, new KbController.KbRequest(k[0], k[1],
                    com.fasterxml.jackson.databind.node.TextNode.valueOf(k[2]), smHr, null, null), admin);
        }

        LocalDate today = LocalDate.now(Times.ZONE);
        Object[][] assets = {
                {"CERT", "SSL 인증서 *.example.local", "web01", -3},
                {"LICENSE", "리포팅 솔루션 라이선스", null, 1},
                {"ACCOUNT", "DB 운영 계정(hr_app) 비밀번호", "db01", 5},
                {"CERT", "공동인증서(전자결재 연동)", null, 7},
                {"DOMAIN", "hr.example.local", null, 20},
                {"LICENSE", "WAS 기술지원 계약", null, 30},
                {"SERVER", "WEB 서버 web01", "10.0.0.11", 60},
                {"SERVER", "WAS 서버 was01", "10.0.0.21", 120},
                {"SERVER", "DB 서버 db01", "10.0.0.31", 365},
                {"ACCOUNT", "배치 실행 계정(batch)", "was01", null}};
        for (Object[] a : assets) {
            routine.insertAsset(pid, new RoutineController.OpsAssetRequest((String) a[0], (String) a[1], (String) a[2],
                    "서운영", opId, a[3] == null ? null : today.plusDays((Integer) a[3]), null));
        }

        var j1 = routine.insertJob(pid, new RoutineController.RoutineJobRequest("일일 백업 결과 확인",
                new RoutineController.Rule("DAILY", null, null), opId, null, List.of("DB 백업 로그", "파일 백업 용량")));
        routine.insertJob(pid, new RoutineController.RoutineJobRequest("주간 보안 점검",
                new RoutineController.Rule("WEEKLY", List.of(1), null), opId, null, List.of("접근 로그", "계정 잠금 현황")));
        routine.insertJob(pid, new RoutineController.RoutineJobRequest("주간 디스크 사용량 점검",
                new RoutineController.Rule("WEEKLY", List.of(5), null), opId, null, List.of("WEB", "WAS", "DB")));
        routine.insertJob(pid, new RoutineController.RoutineJobRequest("월마감 인사 배치 확인",
                new RoutineController.Rule("MONTHLY", null, 25), opId, null, List.of("발령 반영", "급여 연동 파일")));
        for (int d = 1; d <= 7; d++) {
            LocalDate day = today.minusDays(d);
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                jdbc.update("INSERT INTO SM_ROUTINE_CHECK (JOB_ID, CHECK_DT, DONE, DONE_BY, DONE_AT, NOTE) VALUES (?, ?, TRUE, ?, ?, NULL)",
                        j1.jobId(), day, opId, day.atTime(9, 30).atZone(Times.ZONE).toOffsetDateTime());
            }
        }
    }
}
