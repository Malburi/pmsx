package kr.pmsx.rs;

import kr.pmsx.common.ApiException;
import kr.pmsx.pj.PjSupport;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 배정·작업일지 대상(WBS·SCREEN·SR) 의 프로젝트·표시 이름 해석. SR 은 R3 테이블(SM_SR)이 있을 때만. */
@Component
public class WorkTargets {

    public record Target(String type, String id, String projectId, String label) {
    }

    private final JdbcTemplate jdbc;

    public WorkTargets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Target resolve(String field, String type, String id) {
        if (type == null) {
            throw PjSupport.validation(field + ".targetType", "필수 입력입니다.");
        }
        if (id == null || id.isBlank()) {
            throw PjSupport.validation(field + ".targetId", "필수 입력입니다.");
        }
        return switch (type) {
            case "WBS" -> jdbc.query("SELECT PROJECT_ID, WBS_NO, NAME FROM PJ_WBS WHERE WBS_ID = ?",
                    (rs, i) -> new Target(type, id, rs.getString(1), rs.getString(2) + " " + rs.getString(3)), id)
                    .stream().findFirst().orElseThrow(() -> ApiException.notFound("WBS 항목"));
            case "SCREEN" -> jdbc.query("""
                    SELECT Y.PROJECT_ID, S.SCREEN_CODE, S.NAME FROM AS_SCREEN S JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = S.SYSTEM_ID
                     WHERE S.SCREEN_ID = ?""",
                    (rs, i) -> new Target(type, id, rs.getString(1), "[화면분석] " + rs.getString(2) + " "
                            + rs.getString(3)), id).stream().findFirst()
                    .orElseThrow(() -> ApiException.notFound("화면"));
            case "SR" -> {
                try {
                    yield jdbc.query("SELECT PROJECT_ID, SR_NO, TITLE FROM SM_SR WHERE SR_ID = ?",
                            (rs, i) -> new Target(type, id, rs.getString(1), rs.getString(2) + " " + rs.getString(3)),
                            id).stream().findFirst().orElseThrow(() -> ApiException.notFound("SR"));
                } catch (DataAccessException e) {
                    throw PjSupport.validation(field + ".targetType", "SR 은 아직 지원하지 않습니다(R3).");
                }
            }
            default -> throw PjSupport.validation(field + ".targetType", "허용 값: WBS, SCREEN, SR");
        };
    }
}
