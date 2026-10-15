package kr.pmsx.cm;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.dl.DlController;
import kr.pmsx.pj.ProjectSeq;
import kr.pmsx.pj.wbs.MethodologyController;
import kr.pmsx.pj.wbs.WbsService;
import kr.pmsx.pj.wbs.WbsService.WbsItem;
import kr.pmsx.pj.wbs.WbsTools;
import kr.pmsx.qa.IssueController;
import kr.pmsx.qa.QualityController;
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
 * R2 데모 데이터 (pmsx.seed.demo=true, local 프로필 전용). 시드 프로젝트에 WBS 가 없을 때 1회만:
 * 인력 10명·멤버, 방법론 WBS(진행 중 상태), MM 계획, 승인된 작업일지, 요구사항·이슈·리스크·변경요청·테스트·결함·회의록.
 */
@Component
@Order(30)
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private record Person(String login, String name, String org, String company, String grade, String role,
                          String[] skills) {
    }

    private final JdbcTemplate jdbc;
    private final UserDao users;
    private final PasswordEncoder encoder;
    private final PmsxProperties props;
    private final WbsService wbs;
    private final WbsTools tools;
    private final DlController dl;
    private final IssueController issues;
    private final QualityController quality;
    private final ProjectSeq seq;

    public DemoSeeder(JdbcTemplate jdbc, UserDao users, PasswordEncoder encoder, PmsxProperties props, WbsService wbs,
            WbsTools tools, DlController dl, IssueController issues, QualityController quality, ProjectSeq seq) {
        this.jdbc = jdbc;
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.wbs = wbs;
        this.tools = tools;
        this.dl = dl;
        this.issues = issues;
        this.quality = quality;
        this.seq = seq;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!props.demoSeed()) {
            return;
        }
        String projectId = jdbc.query("SELECT PROJECT_ID FROM PJ_PROJECT WHERE NAME LIKE '%차세대%' ORDER BY CREATED_AT LIMIT 1",
                (rs, i) -> rs.getString(1)).stream().findFirst().orElse(null);
        if (projectId == null) {
            return;
        }
        Integer wbsCount = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_WBS WHERE PROJECT_ID = ?", Integer.class,
                projectId);
        if (wbsCount != null && wbsCount > 0) {
            return;
        }
        String adminId = users.findByLoginId("admin").map(u -> u.userId()).orElse(null);
        if (adminId == null) {
            return;
        }
        AuthUser admin = new AuthUser(adminId, "admin", "관리자", List.of("SYS_ADMIN"));
        LocalDate today = LocalDate.now(Times.ZONE);
        LocalDate start = today.minusWeeks(8).with(DayOfWeek.MONDAY);
        jdbc.update("UPDATE PJ_PROJECT SET START_DT = ?, END_DT = ? WHERE PROJECT_ID = ?", start,
                start.plusMonths(12).minusDays(1), projectId);

        // ── 인력 ──
        List<Person> people = List.of(
                new Person("pl.kim", "김파트", "INTERNAL", "PMS-X 컨설팅", "HIGH", "PL", new String[] {"Java:5", "PM:4"}),
                new Person("dev.lee", "이개발", "INTERNAL", "PMS-X 컨설팅", "MIDDLE", "DEV", new String[] {"Java:4", "Vue:3"}),
                new Person("dev.park", "박개발", "INTERNAL", "PMS-X 컨설팅", "JUNIOR", "DEV", new String[] {"Java:3", "SQL:3"}),
                new Person("dev.choi", "최개발", "PARTNER", "한빛소프트", "MIDDLE", "DEV", new String[] {"Nexacro:4", "Oracle:4"}),
                new Person("dev.jung", "정개발", "PARTNER", "한빛소프트", "HIGH", "DEV", new String[] {"Java:5", "Spring:5"}),
                new Person("qa.han", "한품질", "INTERNAL", "PMS-X 컨설팅", "MIDDLE", "QA", new String[] {"테스트:4"}),
                new Person("da.yoon", "윤데이터", "PARTNER", "데이터웍스", "SPECIAL", "PARTNER", new String[] {"Oracle:5", "이행:4"}),
                new Person("cust.oh", "오고객", "CUSTOMER", "○○공사", null, "CUSTOMER", new String[] {}),
                new Person("op.seo", "서운영", "INTERNAL", "PMS-X 컨설팅", "MIDDLE", "OPERATOR", new String[] {"리눅스:4"}));
        String hash = encoder.encode(DataSeeder.SEED_PASSWORD);
        Map<String, String> ids = new LinkedHashMap<>();
        for (Person p : people) {
            String uid = users.findByLoginId(p.login()).map(u -> u.userId())
                    .orElseGet(() -> users.insert(p.login(), hash, p.name(), List.of()));
            ids.put(p.login(), uid);
            jdbc.update("UPDATE CM_USER SET ORG_TYPE = ?, COMPANY = ?, GRADE = ? WHERE USER_ID = ?", p.org(), p.company(),
                    p.grade(), uid);
            for (String s : p.skills()) {
                String[] kv = s.split(":");
                jdbc.update("DELETE FROM RS_SKILL WHERE USER_ID = ? AND SKILL = ?", uid, kv[0]);
                jdbc.update("INSERT INTO RS_SKILL (USER_ID, SKILL, LVL) VALUES (?, ?, ?)", uid, kv[0],
                        Integer.parseInt(kv[1]));
            }
            Integer m = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                    Integer.class, projectId, uid);
            if (m == null || m == 0) {
                jdbc.update("INSERT INTO PJ_MEMBER (PROJECT_ID, USER_ID, ROLE_ID, IN_DT) VALUES (?, ?, ?, ?)", projectId,
                        uid, p.role(), start);
            }
        }
        String analystId = users.findByLoginId("analyst").map(u -> u.userId()).orElse(null);
        jdbc.update("UPDATE CM_USER SET GRADE = 'MIDDLE', COMPANY = 'PMS-X 컨설팅' WHERE USER_ID IN (?, ?)", adminId,
                analystId);

        // ── WBS (방법론 생성 → 세부 작업·마일스톤 추가 → 진행 상태) ──
        tools.generate(projectId, MethodologyController.STANDARD_ID, start, true, adminId);
        List<WbsItem> items = new ArrayList<>(wbs.list(projectId));
        List<WbsItem> extra = new ArrayList<>();
        WbsItem dev = items.stream().filter(i -> i.name().equals("업무 기능 개발")).findFirst().orElse(null);
        if (dev != null) {
            String[] modules = {"인사 관리 기능", "급여 관리 기능", "근태 관리 기능", "교육 관리 기능"};
            LocalDate s = dev.planStart();
            long span = java.time.temporal.ChronoUnit.DAYS.between(dev.planStart(), dev.planEnd()) / modules.length;
            for (int i = 0; i < modules.length; i++) {
                LocalDate ms = s.plusDays(span * i);
                LocalDate me = i == modules.length - 1 ? dev.planEnd() : s.plusDays(span * (i + 1) - 1);
                extra.add(new WbsItem("tmp-dev-" + i, dev.wbsId(), null, modules[i], 0, ms, me, null, null, 1.5, 0, false,
                        "TODO", null, null, List.of(), false, null, null));
            }
        }
        WbsItem asis = items.stream().filter(i -> i.name().startsWith("현행 시스템")).findFirst().orElse(null);
        if (asis != null) {
            String[] sub = {"인사시스템 화면 분석", "급여시스템 화면 분석", "DB·인터페이스 분석"};
            long span = java.time.temporal.ChronoUnit.DAYS.between(asis.planStart(), asis.planEnd()) / sub.length;
            for (int i = 0; i < sub.length; i++) {
                LocalDate ms = asis.planStart().plusDays(span * i);
                LocalDate me = i == sub.length - 1 ? asis.planEnd() : asis.planStart().plusDays(span * (i + 1) - 1);
                extra.add(new WbsItem("tmp-asis-" + i, asis.wbsId(), null, sub[i], 0, ms, me, null, null, 1, 0, false,
                        "TODO", null, null, List.of(), false, null, null));
            }
        }
        WbsItem ui = items.stream().filter(i -> i.name().startsWith("화면·인터페이스")).findFirst().orElse(null);
        if (ui != null) {
            String[] sub = {"인사 화면 설계", "급여 화면 설계", "외부 인터페이스 정의"};
            long span = java.time.temporal.ChronoUnit.DAYS.between(ui.planStart(), ui.planEnd()) / sub.length;
            for (int i = 0; i < sub.length; i++) {
                LocalDate ms = ui.planStart().plusDays(span * i);
                LocalDate me = i == sub.length - 1 ? ui.planEnd() : ui.planStart().plusDays(span * (i + 1) - 1);
                extra.add(new WbsItem("tmp-ui-" + i, ui.wbsId(), null, sub[i], 0, ms, me, null, null, 1, 0, false,
                        "TODO", null, null, List.of(), false, null, null));
            }
        }
        List<WbsItem> merged = new ArrayList<>();
        WbsItem lastPhase = null;
        for (WbsItem it : items) {
            merged.add(it);
            for (WbsItem e : extra) {
                if (e.parentId().equals(it.wbsId())) {
                    merged.add(e);
                }
            }
            if (it.parentId() == null) {
                lastPhase = it;
            }
        }
        LocalDate end = items.stream().map(WbsItem::planEnd).filter(x -> x != null).max(LocalDate::compareTo)
                .orElse(start.plusMonths(10));
        merged.add(new WbsItem("tmp-ms-0", null, null, "착수보고", 0, start.plusDays(4), start.plusDays(4), null, null, 0.1,
                0, true, "TODO", null, null, List.of(), false, null, null));
        merged.add(new WbsItem("tmp-ms-1", null, null, "중간보고", 0, start.plusMonths(4), start.plusMonths(4), null,
                null, 0.1, 0, true, "TODO", null, null, List.of(), false, null, null));
        merged.add(new WbsItem("tmp-ms-2", null, null, "시스템 오픈", 0, end, end, null, null, 0.1, 0, true, "TODO",
                null, null, List.of(), false, null, null));
        if (lastPhase == null) {
            return;
        }
        // 진행 상태: 오늘 이전 종료 = 완료, 진행 중 = 경과율 근처, 담당 배정
        List<String> devs = List.of(ids.get("dev.lee"), ids.get("dev.park"), ids.get("dev.choi"), ids.get("dev.jung"));
        List<WbsItem> finalList = new ArrayList<>();
        int k = 0;
        for (WbsItem it : merged) {
            boolean leaf = merged.stream().noneMatch(c -> it.wbsId().equals(c.parentId()));
            double progress = 0;
            String status = "TODO";
            LocalDate as = null;
            LocalDate ae = null;
            if (leaf && it.planStart() != null && it.planEnd() != null) {
                if (it.planEnd().isBefore(today)) {
                    progress = 100;
                    status = "DONE";
                    as = it.planStart();
                    ae = it.planEnd();
                } else if (!it.planStart().isAfter(today)) {
                    long total = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(it.planStart(), it.planEnd()));
                    long done = java.time.temporal.ChronoUnit.DAYS.between(it.planStart(), today);
                    progress = Math.max(5, Math.round(done * 100.0 / total) - 10);
                    status = "IN_PROGRESS";
                    as = it.planStart();
                }
            }
            List<UserRef> assignees = null;
            if (leaf && !it.milestone()) {
                String who = it.name().contains("분석") ? analystId : it.name().contains("시험") ? ids.get("qa.han")
                        : it.name().contains("이행") || it.name().contains("데이터") ? ids.get("da.yoon")
                        : it.name().contains("계획") || it.name().contains("요구") ? ids.get("pl.kim")
                        : devs.get(k++ % devs.size());
                assignees = List.of(new UserRef(who, ""));
            }
            finalList.add(new WbsItem(it.wbsId(), it.parentId(), it.wbsNo(), it.name(), 0, it.planStart(), it.planEnd(),
                    as, ae, it.weight(), progress, it.milestone(), status, assignees, it.deliverableId(), it.deps(), false,
                    null, null));
        }
        // 마일스톤은 맨 앞(착수) / 해당 위치에 상관없이 목록 끝에 둔다
        wbs.saveAll(projectId, finalList, adminId);
        tools.createBaseline(projectId, "착수 베이스라인", adminId);

        // ── MM 계획 (12개월) ──
        List<String> members = jdbc.queryForList("SELECT USER_ID FROM PJ_MEMBER WHERE PROJECT_ID = ? AND ROLE_ID <> 'CUSTOMER'",
                String.class, projectId);
        for (String uid : members) {
            for (int i = 0; i < 12; i++) {
                String ym = YearMonth.from(start).plusMonths(i).toString();
                jdbc.update("INSERT INTO RS_MM_PLAN (PROJECT_ID, USER_ID, YM, PLAN_MM) VALUES (?, ?, ?, ?)", projectId,
                        uid, ym, BigDecimal.valueOf(uid.equals(ids.get("da.yoon")) && i < 8 ? 0.5 : 1.0));
            }
        }

        // ── 승인된 작업일지 (지난 2주, 개발자·분석자) ──
        String someWbs = jdbc.query("SELECT W.WBS_ID FROM PJ_WBS W WHERE W.PROJECT_ID = ? AND W.STATUS = 'IN_PROGRESS'"
                + " AND NOT EXISTS (SELECT 1 FROM PJ_WBS C WHERE C.PARENT_ID = W.WBS_ID) ORDER BY W.ORD LIMIT 1",
                (rs, i) -> rs.getString(1), projectId).stream().findFirst().orElse(null);
        if (someWbs != null) {
            String label = jdbc.queryForObject("SELECT WBS_NO || ' ' || NAME FROM PJ_WBS WHERE WBS_ID = ?", String.class,
                    someWbs);
            for (String uid : List.of(ids.get("dev.lee"), ids.get("dev.park"), analystId)) {
                for (int w = 1; w <= 2; w++) {
                    LocalDate ws = today.minusWeeks(w).with(DayOfWeek.MONDAY);
                    String tid = Ulid.next();
                    jdbc.update("""
                            INSERT INTO RS_TIMESHEET (TIMESHEET_ID, USER_ID, WEEK_START, STATUS, SUBMITTED_AT, DECIDED_AT,
                                                      DECIDED_BY, UPDATED_AT)
                            VALUES (?, ?, ?, 'APPROVED', ?, ?, ?, ?)""", tid, uid, ws, Times.now(), Times.now(), adminId,
                            Times.now());
                    for (int d = 0; d < 5; d++) {
                        jdbc.update("""
                                INSERT INTO RS_TIMESHEET_ENTRY (ENTRY_ID, TIMESHEET_ID, WORK_DT, TARGET_TYPE, TARGET_ID,
                                                                TARGET_LABEL, PROJECT_ID, HOURS, NOTE, ORD)
                                VALUES (?, ?, ?, 'WBS', ?, ?, ?, 8, NULL, ?)""", Ulid.next(), tid, ws.plusDays(d), someWbs,
                                label, projectId, d);
                    }
                }
            }
        }

        // ── 요구사항·테스트·결함·이슈·리스크·CR·회의록 ──
        String[][] reqs = {
                {"사원 정보 등록·수정·삭제", "인사"}, {"부서 조직도 조회", "인사"}, {"월 급여 자동 계산", "급여"},
                {"급여 명세서 출력", "급여"}, {"근태 신청·승인 결재", "근태"}, {"교육 이력 관리", "교육"},
                {"개인정보 마스킹", "보안"}, {"기존 데이터 이관", "이행"}};
        List<String> reqIds = new ArrayList<>();
        for (int i = 0; i < reqs.length; i++) {
            reqIds.add(dl.insertRequirement(projectId, new DlController.Requirement(null, null, reqs[i][0], null,
                    "제안요청서 " + (3 + i) + "장", reqs[i][1], i < 3 ? "H" : "M", i < 4 ? "ACCEPTED" : "PROPOSED", null),
                    adminId).reqId());
        }
        var scen = quality.createScenario(admin, projectId, new QualityController.TestScenario(null, "INTEGRATION",
                "인사 통합 시나리오", List.of(
                        new QualityController.TestCase(null, null, "사원 신규 등록", null, "1. 신규 2. 필수값 입력 3. 저장",
                                "저장 완료 메시지", List.of(reqIds.get(0)), null, null),
                        new QualityController.TestCase(null, null, "사원 미래 입사일 입력 차단", null, "입사일에 미래 날짜 입력 후 저장",
                                "오류 메시지", List.of(reqIds.get(0)), null, null),
                        new QualityController.TestCase(null, null, "부서 조직도 조회", null, "부서 트리 조회", "조직도 표시",
                                List.of(reqIds.get(1)), null, null))));
        quality.createScenario(admin, projectId, new QualityController.TestScenario(null, "INTEGRATION", "급여 통합 시나리오",
                List.of(new QualityController.TestCase(null, null, "월 급여 계산", null, "대상월 선택 후 계산", "급여 계산 완료",
                        List.of(reqIds.get(2)), null, null))));
        var round = quality.createRound(admin, projectId, new QualityController.TestRound(null, "통합테스트 1차",
                "INTEGRATION", null));
        var d1 = quality.insertDefect(projectId, new QualityController.DefectRequest("미래 입사일이 저장됨",
                "입사일 검증 누락", "MAJOR", "H", ids.get("dev.lee"), scen.cases().get(1).caseId(), null, null), admin);
        quality.insertDefect(projectId, new QualityController.DefectRequest("조직도 하위부서 누락", null, "MINOR", "M",
                ids.get("dev.park"), null, null, null), admin);
        quality.insertDefect(projectId, new QualityController.DefectRequest("급여 계산 시 오류 발생", "NullPointerException",
                "CRITICAL", "H", ids.get("dev.jung"), null, null, null), admin);
        quality.saveResults(admin, round.roundId(), List.of(
                new QualityController.TestResult(scen.cases().get(0).caseId(), "PASS", null, null),
                new QualityController.TestResult(scen.cases().get(1).caseId(), "FAIL", "미래 날짜 저장됨", d1.defectId()),
                new QualityController.TestResult(scen.cases().get(2).caseId(), "BLOCK", "부서 데이터 미이관", null)));

        issues.insertIssue(projectId, new IssueController.IssueRequest("테스트 서버 접속 계정 미발급", null, "H", "OPEN",
                ids.get("pl.kim"), today.minusDays(2), null), null, admin);
        issues.insertIssue(projectId, new IssueController.IssueRequest("급여 계산 규칙 고객 확인 필요", "직급별 수당 기준 불명확",
                "M", "IN_PROGRESS", ids.get("pl.kim"), today.plusDays(5), null), null, admin);
        issues.insertIssue(projectId, new IssueController.IssueRequest("레거시 DB 문자셋 확인", null, "L", "RESOLVED",
                ids.get("da.yoon"), today.minusDays(10), null), null, admin);
        for (Object[] r : new Object[][] {{"핵심 인력 이탈", 2, 5, "대체 인력 풀 확보"},
                {"레거시 데이터 품질 불량", 4, 4, "이관 리허설 2회"}, {"고객 요구사항 추가", 3, 3, "CR 절차 운영"},
                {"성능 목표 미달", 2, 4, "성능 테스트 조기 수행"}}) {
            jdbc.update("""
                    INSERT INTO QA_RISK (RISK_ID, PROJECT_ID, RISK_NO, TITLE, PROBABILITY, IMPACT, RESPONSE, STATUS, OWNER_ID,
                                         CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'IDENTIFIED', ?, ?, ?, ?, ?)""", Ulid.next(), projectId,
                    seq.next(projectId, "RSK"), r[0], r[1], r[2], r[3], ids.get("pl.kim"), Times.now(), adminId, Times.now(),
                    adminId);
        }
        jdbc.update("""
                INSERT INTO QA_CR (CR_ID, PROJECT_ID, CR_NO, TITLE, REASON, REQUESTER, IMPACT_SCOPE, MM_DELTA, SCHEDULE_DELTA,
                                   STATUS, REQ_IDS, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, '모바일 급여명세서 추가', '현업 요청', '오고객', '급여 명세서 화면 1식', 1.5, 10, 'ANALYZED', ?, ?, ?, ?, ?)""",
                Ulid.next(), projectId, seq.next(projectId, "CR"), reqIds.get(3), Times.now(), adminId, Times.now(),
                adminId);
        String mid = Ulid.next();
        jdbc.update("""
                INSERT INTO QA_MEETING (MEETING_ID, PROJECT_ID, MEETING_NO, TITLE, MEETING_AT, ATTENDEES, CONTENT, CREATED_AT,
                                        CREATED_BY, UPDATED_AT)
                VALUES (?, ?, ?, '주간 진척 점검 회의', ?, '관리자, 김파트, 오고객', '- 분석 단계 진척 점검\n- 테스트 계정 발급 요청', ?, ?, ?)""",
                mid, projectId, seq.next(projectId, "MTG"), Times.now().minusDays(3), Times.now(), adminId, Times.now());
        jdbc.update("INSERT INTO QA_ACTION_ITEM (ITEM_ID, MEETING_ID, PROJECT_ID, CONTENT, OWNER_ID, DUE_DT, DONE, ORD)"
                + " VALUES (?, ?, ?, '테스트 계정 발급 요청 공문 발송', ?, ?, FALSE, 0)", Ulid.next(), mid, projectId,
                ids.get("pl.kim"), today.plusDays(1));
        jdbc.update("""
                INSERT INTO QA_DECISION (DECISION_ID, PROJECT_ID, TITLE, CONTENT, RATIONALE, DECIDED_BY, DECIDED_AT, CREATED_AT,
                                         CREATED_BY)
                VALUES (?, ?, '화면 기술 표준: Vue 3 + Vuetify', '신규 화면은 Vue 3 기반으로 개발', '사내 표준·유지보수성', 'PM', ?, ?, ?)""",
                Ulid.next(), projectId, today.minusDays(30), Times.now(), adminId);
        log.info("R2 데모 데이터 생성: 인력 {}명, WBS {}행", people.size(), finalList.size());
    }
}
