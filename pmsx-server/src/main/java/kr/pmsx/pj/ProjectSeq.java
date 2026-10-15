package kr.pmsx.pj;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 프로젝트별 일련번호 (docs/12 머리말): ISS-0001, RSK-0001, CR-0001, DEF-0001, REQ-0001, TC-0001, MTG-0001 */
@Component
public class ProjectSeq {

    private final JdbcTemplate jdbc;

    public ProjectSeq(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 다음 번호 (호출 트랜잭션 안에서 행 잠금으로 증가) */
    @Transactional(propagation = Propagation.REQUIRED)
    public String next(String projectId, String prefix) {
        int n = jdbc.update("UPDATE PJ_SEQ SET NEXT_NO = NEXT_NO + 1 WHERE PROJECT_ID = ? AND PREFIX = ?", projectId,
                prefix);
        if (n == 0) {
            try {
                jdbc.update("INSERT INTO PJ_SEQ (PROJECT_ID, PREFIX, NEXT_NO) VALUES (?, ?, 2)", projectId, prefix);
                return format(prefix, 1);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                jdbc.update("UPDATE PJ_SEQ SET NEXT_NO = NEXT_NO + 1 WHERE PROJECT_ID = ? AND PREFIX = ?", projectId,
                        prefix);
            }
        }
        Integer next = jdbc.queryForObject("SELECT NEXT_NO FROM PJ_SEQ WHERE PROJECT_ID = ? AND PREFIX = ?",
                Integer.class, projectId, prefix);
        return format(prefix, next - 1);
    }

    /** 가져오기 등으로 번호를 직접 지정한 경우 이후 번호가 겹치지 않게 맞춘다 */
    public void bumpTo(String projectId, String prefix, String no) {
        if (no == null || !no.startsWith(prefix + "-")) {
            return;
        }
        int v;
        try {
            v = Integer.parseInt(no.substring(prefix.length() + 1));
        } catch (NumberFormatException e) {
            return;
        }
        int n = jdbc.update("UPDATE PJ_SEQ SET NEXT_NO = ? WHERE PROJECT_ID = ? AND PREFIX = ? AND NEXT_NO <= ?",
                v + 1, projectId, prefix, v);
        if (n == 0) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_SEQ WHERE PROJECT_ID = ? AND PREFIX = ?",
                    Integer.class, projectId, prefix);
            if (exists == null || exists == 0) {
                jdbc.update("INSERT INTO PJ_SEQ (PROJECT_ID, PREFIX, NEXT_NO) VALUES (?, ?, ?)", projectId, prefix,
                        v + 1);
            }
        }
    }

    private static String format(String prefix, int n) {
        return prefix + "-" + String.format("%04d", n);
    }
}
