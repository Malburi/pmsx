package kr.pmsx.dl;

import java.util.Map;
import java.util.Optional;
import kr.pmsx.common.ApiException;
import kr.pmsx.pj.PjSupport;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 추적 링크 대상 유형(TraceType)별 프로젝트·표시 라벨 해석 (docs/12 §5). SR 은 R3 테이블이 있을 때. */
@Component
public class TraceRegistry {

    public record Ref(String type, String id, String projectId, String label) {
    }

    /** 유형 → [프로젝트·라벨 SQL] (첫 컬럼 PROJECT_ID, 둘째 라벨) */
    private static final Map<String, String> SQL = Map.ofEntries(
            Map.entry("REQ", "SELECT PROJECT_ID, REQ_NO || ' ' || TITLE FROM DL_REQUIREMENT WHERE REQ_ID = ?"),
            Map.entry("SCREEN", "SELECT Y.PROJECT_ID, S.SCREEN_CODE || ' ' || S.NAME FROM AS_SCREEN S"
                    + " JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = S.SYSTEM_ID WHERE S.SCREEN_ID = ?"),
            Map.entry("ELEMENT", "SELECT Y.PROJECT_ID, S.SCREEN_CODE || ' > ' || COALESCE(E.LABEL, E.ELEMENT_CODE)"
                    + " FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID"
                    + " JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = E.SYSTEM_ID WHERE E.ELEMENT_ID = ?"),
            Map.entry("NODE", "SELECT Y.PROJECT_ID, N.NAME FROM AS_NODE N JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = N.SYSTEM_ID"
                    + " WHERE N.NODE_ID = ?"),
            Map.entry("TC", "SELECT PROJECT_ID, TC_NO || ' ' || TITLE FROM QA_TEST_CASE WHERE CASE_ID = ?"),
            Map.entry("DEFECT", "SELECT PROJECT_ID, DEFECT_NO || ' ' || TITLE FROM QA_DEFECT WHERE DEFECT_ID = ?"),
            Map.entry("CR", "SELECT PROJECT_ID, CR_NO || ' ' || TITLE FROM QA_CR WHERE CR_ID = ?"),
            Map.entry("ISSUE", "SELECT PROJECT_ID, ISSUE_NO || ' ' || TITLE FROM QA_ISSUE WHERE ISSUE_ID = ?"),
            Map.entry("DELIVERABLE", "SELECT PROJECT_ID, NAME FROM DL_DELIVERABLE WHERE DELIVERABLE_ID = ?"),
            Map.entry("WBS", "SELECT PROJECT_ID, WBS_NO || ' ' || NAME FROM PJ_WBS WHERE WBS_ID = ?"),
            Map.entry("SR", "SELECT PROJECT_ID, SR_NO || ' ' || TITLE FROM SM_SR WHERE SR_ID = ?"));

    private final JdbcTemplate jdbc;

    public TraceRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean known(String type) {
        return type != null && SQL.containsKey(type);
    }

    public Optional<Ref> find(String type, String id) {
        String sql = SQL.get(type);
        if (sql == null || id == null) {
            return Optional.empty();
        }
        try {
            return jdbc.query(sql, (rs, i) -> new Ref(type, id, rs.getString(1), rs.getString(2)), id).stream()
                    .findFirst();
        } catch (DataAccessException e) {
            return Optional.empty();   // 아직 없는 테이블(SR 등)
        }
    }

    public Ref require(String field, String type, String id) {
        if (!known(type)) {
            throw PjSupport.validation(field + "Type", "허용 값: " + String.join(", ", SQL.keySet()));
        }
        return find(type, id).orElseThrow(() -> ApiException.notFound(type + " 대상"));
    }

    public String label(String type, String id) {
        return find(type, id).map(Ref::label).orElse("(삭제됨) " + id);
    }
}
