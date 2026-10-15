package kr.pmsx.pj;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 프로젝트 목록·등록·수정 (08 §2, docs/12 §1). 등록은 SYS_ADMIN·PMO, 수정은 SYS_ADMIN·PM. */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    public record ProjectDto(String projectId, String type, String name, String customer, LocalDate startDate,
                             LocalDate endDate, BigDecimal contractMm, String pmUserId, String pmName,
                             String methodologyId, String originProjectId) {
    }

    public record ProjectRequest(String type, String name, String customer, LocalDate startDate, LocalDate endDate,
                                 BigDecimal contractMm, String pmUserId, String methodologyId) {
    }

    private static final String COLS = "SELECT P.PROJECT_ID, P.TYPE, P.NAME, P.CUSTOMER, P.START_DT, P.END_DT,"
            + " P.CONTRACT_MM, P.METHODOLOGY_ID, P.ORIGIN_PROJECT_ID,"
            + " (SELECT MIN(M2.USER_ID) FROM PJ_MEMBER M2 WHERE M2.PROJECT_ID = P.PROJECT_ID AND M2.ROLE_ID = 'PM') PM_ID"
            + " FROM PJ_PROJECT P";

    private static final String PM_NAME_SQL = "SELECT NAME FROM CM_USER WHERE USER_ID = ?";

    private static final RowMapper<ProjectDto> MAPPER = (rs, i) -> new ProjectDto(rs.getString("PROJECT_ID"),
            rs.getString("TYPE"), rs.getString("NAME"), rs.getString("CUSTOMER"),
            rs.getObject("START_DT", LocalDate.class), rs.getObject("END_DT", LocalDate.class),
            rs.getBigDecimal("CONTRACT_MM"), rs.getString("PM_ID"), null, rs.getString("METHODOLOGY_ID"),
            rs.getString("ORIGIN_PROJECT_ID"));

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final PjSupport support;
    private final AuditService audit;
    private final org.springframework.context.ApplicationEventPublisher events;

    public ProjectController(JdbcTemplate jdbc, ProjectAccess access, PjSupport support, AuditService audit,
            org.springframework.context.ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.access = access;
        this.support = support;
        this.audit = audit;
        this.events = events;
    }

    @GetMapping
    public List<ProjectDto> list(@AuthenticationPrincipal AuthUser user) {
        if (access.seesAll(user)) {
            return withPm(jdbc.query(COLS + " ORDER BY P.START_DT DESC, P.NAME", MAPPER));
        }
        return withPm(jdbc.query(COLS + " JOIN PJ_MEMBER M ON M.PROJECT_ID = P.PROJECT_ID WHERE M.USER_ID = ?"
                + " ORDER BY P.START_DT DESC, P.NAME", MAPPER, user.userId()));
    }

    /** PM 이름 채우기 */
    private List<ProjectDto> withPm(List<ProjectDto> list) {
        return list.stream().map(p -> p.pmUserId() == null ? p : new ProjectDto(p.projectId(), p.type(), p.name(),
                p.customer(), p.startDate(), p.endDate(), p.contractMm(), p.pmUserId(),
                jdbc.queryForList(PM_NAME_SQL, String.class, p.pmUserId()).stream().findFirst().orElse(null),
                p.methodologyId(), p.originProjectId())).toList();
    }

    @GetMapping("/{projectId}")
    public ProjectDto get(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return one(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProjectDto create(@AuthenticationPrincipal AuthUser user, @RequestBody ProjectRequest req) {
        if (!user.isSysAdmin() && !user.isPmo()) {
            throw ApiException.forbidden();
        }
        validate(req);
        String id = Ulid.next();
        jdbc.update("""
                INSERT INTO PJ_PROJECT (PROJECT_ID, TYPE, NAME, CUSTOMER, START_DT, END_DT, CONTRACT_MM, METHODOLOGY_ID,
                                        CREATED_AT, CREATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, req.type(), req.name().trim(),
                PjSupport.optional("customer", req.customer(), 200), req.startDate(), req.endDate(), req.contractMm(),
                req.methodologyId(), Times.now(), user.userId());
        if (req.pmUserId() != null) {
            jdbc.update("INSERT INTO PJ_MEMBER (PROJECT_ID, USER_ID, ROLE_ID, IN_DT) VALUES (?, ?, 'PM', ?)", id,
                    req.pmUserId(), req.startDate());
        }
        audit.log(Action.CREATE, "project:" + id, "프로젝트 등록 " + req.name(), id);
        events.publishEvent(new ProjectSavedEvent(id, req.type()));
        return one(id);
    }

    @PutMapping("/{projectId}")
    @Transactional
    public ProjectDto update(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody ProjectRequest req) {
        access.forProject(user, projectId).require(Set.of("SYS_ADMIN", "PM"));
        validate(req);
        jdbc.update("""
                UPDATE PJ_PROJECT SET TYPE = ?, NAME = ?, CUSTOMER = ?, START_DT = ?, END_DT = ?, CONTRACT_MM = ?,
                       METHODOLOGY_ID = ?, UPDATED_AT = ?, UPDATED_BY = ?
                 WHERE PROJECT_ID = ?""", req.type(), req.name().trim(),
                PjSupport.optional("customer", req.customer(), 200), req.startDate(), req.endDate(), req.contractMm(),
                req.methodologyId(), Times.now(), user.userId(), projectId);
        if (req.pmUserId() != null) {
            int n = jdbc.update("UPDATE PJ_MEMBER SET ROLE_ID = 'PM' WHERE PROJECT_ID = ? AND USER_ID = ?", projectId,
                    req.pmUserId());
            if (n == 0) {
                jdbc.update("INSERT INTO PJ_MEMBER (PROJECT_ID, USER_ID, ROLE_ID, IN_DT) VALUES (?, ?, 'PM', ?)",
                        projectId, req.pmUserId(), req.startDate());
            }
        }
        audit.log(Action.UPDATE, "project:" + projectId, "프로젝트 수정", projectId);
        events.publishEvent(new ProjectSavedEvent(projectId, req.type()));
        return one(projectId);
    }

    private void validate(ProjectRequest req) {
        PjSupport.oneOf("type", req.type(), null, "SI", "SM");
        PjSupport.required("name", req.name(), 200);
        if (req.startDate() == null) {
            throw PjSupport.validation("startDate", "시작일을 입력해주세요.");
        }
        if (req.endDate() == null || req.endDate().isBefore(req.startDate())) {
            throw PjSupport.validation("endDate", "종료일은 시작일 이후여야 합니다.");
        }
        if (req.contractMm() != null && req.contractMm().signum() < 0) {
            throw PjSupport.validation("contractMm", "0 이상이어야 합니다.");
        }
        support.requireUser("pmUserId", req.pmUserId());
        if (req.methodologyId() != null) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_METHODOLOGY WHERE METHODOLOGY_ID = ?",
                    Integer.class, req.methodologyId());
            if (n == null || n == 0) {
                throw PjSupport.validation("methodologyId", "방법론을 찾을 수 없습니다.");
            }
        }
    }

    private ProjectDto one(String projectId) {
        return withPm(jdbc.query(COLS + " WHERE P.PROJECT_ID = ?", MAPPER, projectId)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("프로젝트"));
    }
}
