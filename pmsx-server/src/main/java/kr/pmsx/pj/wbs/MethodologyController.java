package kr.pmsx.pj.wbs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 방법론 템플릿 (FR-PJ-002, docs/12 §1). 조회 로그인, 변경 SYS_ADMIN. 기동 시 "SI 표준" 시드. */
@RestController
@RequestMapping("/api/v1/methodologies")
@Order(5)
public class MethodologyController implements ApplicationRunner {

    public record Task(String name, double weight, int durationDays, List<String> deliverables) {
    }

    public record Phase(String name, int order, List<Task> tasks) {
    }

    public record Methodology(String methodologyId, String name, List<Phase> phases) {
    }

    public static final String STANDARD_ID = "01SISTANDARDMETHODOLOGY001";

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final AuditService audit;

    public MethodologyController(JdbcTemplate jdbc, ObjectMapper om, AuditService audit) {
        this.jdbc = jdbc;
        this.om = om;
        this.audit = audit;
    }

    @GetMapping
    public List<Methodology> list() {
        return jdbc.query("SELECT METHODOLOGY_ID, NAME, PHASES_JSON FROM PJ_METHODOLOGY ORDER BY CREATED_AT",
                (rs, i) -> new Methodology(rs.getString(1), rs.getString(2), phases(rs.getString(3))));
    }

    @GetMapping("/{id}")
    public Methodology get(@PathVariable String id) {
        return find(id);
    }

    public Methodology find(String id) {
        return jdbc.query("SELECT METHODOLOGY_ID, NAME, PHASES_JSON FROM PJ_METHODOLOGY WHERE METHODOLOGY_ID = ?",
                (rs, i) -> new Methodology(rs.getString(1), rs.getString(2), phases(rs.getString(3))), id).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("방법론"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Methodology create(@AuthenticationPrincipal AuthUser user, @RequestBody Methodology req) {
        requireAdmin(user);
        String id = Ulid.next();
        jdbc.update("INSERT INTO PJ_METHODOLOGY (METHODOLOGY_ID, NAME, PHASES_JSON, CREATED_AT, CREATED_BY)"
                        + " VALUES (?, ?, ?, ?, ?)", id, PjSupport.required("name", req.name(), 200),
                json(clean(req.phases())), Times.now(), user.userId());
        audit.log(Action.CREATE, "methodology:" + id, "방법론 등록 " + req.name());
        return find(id);
    }

    @PutMapping("/{id}")
    public Methodology update(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
            @RequestBody Methodology req) {
        requireAdmin(user);
        int n = jdbc.update("UPDATE PJ_METHODOLOGY SET NAME = ?, PHASES_JSON = ?, UPDATED_AT = ?, UPDATED_BY = ?"
                        + " WHERE METHODOLOGY_ID = ?", PjSupport.required("name", req.name(), 200),
                json(clean(req.phases())), Times.now(), user.userId(), id);
        if (n == 0) {
            throw ApiException.notFound("방법론");
        }
        audit.log(Action.UPDATE, "methodology:" + id, "방법론 수정");
        return find(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        requireAdmin(user);
        int n = jdbc.update("DELETE FROM PJ_METHODOLOGY WHERE METHODOLOGY_ID = ?", id);
        if (n == 0) {
            throw ApiException.notFound("방법론");
        }
        jdbc.update("UPDATE PJ_PROJECT SET METHODOLOGY_ID = NULL WHERE METHODOLOGY_ID = ?", id);
        audit.log(Action.DELETE, "methodology:" + id, "방법론 삭제");
    }

    private static void requireAdmin(AuthUser user) {
        if (!user.isSysAdmin()) {
            throw ApiException.forbidden();
        }
    }

    private static List<Phase> clean(List<Phase> phases) {
        if (phases == null || phases.isEmpty()) {
            throw PjSupport.validation("phases", "단계를 1개 이상 입력해주세요.");
        }
        List<Phase> out = new ArrayList<>();
        int pi = 0;
        for (Phase p : phases) {
            String pn = PjSupport.required("phases[" + pi + "].name", p.name(), 100);
            List<Task> tasks = new ArrayList<>();
            int ti = 0;
            for (Task t : p.tasks() == null ? List.<Task>of() : p.tasks()) {
                String tn = PjSupport.required("phases[" + pi + "].tasks[" + ti + "].name", t.name(), 300);
                tasks.add(new Task(tn, t.weight() <= 0 ? 1 : t.weight(), Math.max(1, t.durationDays()),
                        t.deliverables() == null ? List.of() : t.deliverables().stream()
                                .filter(d -> d != null && !d.isBlank()).map(String::trim).toList()));
                ti++;
            }
            out.add(new Phase(pn, p.order() == 0 ? pi + 1 : p.order(), tasks));
            pi++;
        }
        out.sort(java.util.Comparator.comparingInt(Phase::order));
        return out;
    }

    private String json(Object v) {
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Phase> phases(String json) {
        try {
            return om.readValue(json, new TypeReference<List<Phase>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 기준 방법론 "SI 표준" 시드 (없을 때만) */
    @Override
    public void run(ApplicationArguments args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_METHODOLOGY WHERE METHODOLOGY_ID = ?", Integer.class,
                STANDARD_ID);
        if (n != null && n > 0) {
            return;
        }
        List<Phase> phases = List.of(
                new Phase("분석", 1, List.of(
                        new Task("착수 및 사업수행계획 수립", 1, 5, List.of("사업수행계획서")),
                        new Task("현행 시스템(As-Is) 분석", 3, 15, List.of("현행시스템 분석서")),
                        new Task("요구사항 정의", 2, 10, List.of("요구사항 정의서", "요구사항 추적표")))),
                new Phase("설계", 2, List.of(
                        new Task("아키텍처 설계", 2, 10, List.of("아키텍처 설계서")),
                        new Task("데이터베이스 설계", 2, 10, List.of("테이블 정의서", "ERD")),
                        new Task("화면·인터페이스 설계", 3, 15, List.of("화면 설계서", "인터페이스 정의서")))),
                new Phase("구현", 3, List.of(
                        new Task("공통 모듈 개발", 2, 10, List.of()),
                        new Task("업무 기능 개발", 6, 40, List.of("프로그램 목록")),
                        new Task("단위 시험", 2, 10, List.of("단위시험 결과서")))),
                new Phase("시험", 4, List.of(
                        new Task("통합 시험", 3, 15, List.of("통합시험 계획서", "통합시험 결과서")),
                        new Task("성능·보안 점검", 1, 5, List.of("시큐어코딩 점검 결과")),
                        new Task("사용자 인수 시험", 2, 10, List.of("인수시험 결과서")))),
                new Phase("이행", 5, List.of(
                        new Task("데이터 이행", 2, 10, List.of("데이터 이행 결과서")),
                        new Task("오픈 및 안정화", 2, 10, List.of("운영 인수인계서")))));
        jdbc.update("INSERT INTO PJ_METHODOLOGY (METHODOLOGY_ID, NAME, PHASES_JSON, CREATED_AT) VALUES (?, ?, ?, ?)",
                STANDARD_ID, "SI 표준 (분석·설계·구현·시험·이행)", json(phases), Times.now());
    }
}
