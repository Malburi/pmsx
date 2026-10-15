package kr.pmsx.cm.code;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공통코드·용어 (FR-CM-004, docs/10 §1). 엑셀 데이터셋(codes, terms)도 이 서비스를 쓴다. */
@Service
public class CodeService {

    public record CodeGroup(@NotBlank(message = "그룹 코드를 입력해주세요.") @Size(max = 50)
                            @Pattern(regexp = "^[A-Z0-9_]+$", message = "그룹 코드는 영문 대문자·숫자·_ 만 쓸 수 있습니다.")
                            String groupCode,
                            @NotBlank(message = "그룹명을 입력해주세요.") @Size(max = 100) String name,
                            @Size(max = 500) String description, boolean system) {
    }

    public record Code(String code, String name, int sortOrder, boolean useYn, String attr1, String attr2) {
    }

    public record Term(String termId, @NotBlank(message = "용어를 입력해주세요.") @Size(max = 200) String term,
                       @Size(max = 100) String abbr,
                       @NotBlank(message = "정의를 입력해주세요.") @Size(max = 2000) String definition,
                       @Size(max = 100) String domain) {
    }

    private static final RowMapper<CodeGroup> GROUP = (rs, i) -> new CodeGroup(rs.getString("GROUP_CODE"),
            rs.getString("NAME"), rs.getString("DESCRIPTION"), rs.getBoolean("SYSTEM_YN"));
    private static final RowMapper<Code> CODE = (rs, i) -> new Code(rs.getString("CODE"), rs.getString("NAME"),
            rs.getInt("SORT_ORD"), rs.getBoolean("USE_YN"), rs.getString("ATTR1"), rs.getString("ATTR2"));
    private static final RowMapper<Term> TERM = (rs, i) -> new Term(rs.getString("TERM_ID"), rs.getString("TERM"),
            rs.getString("ABBR"), rs.getString("DEFINITION"), rs.getString("TERM_DOMAIN"));

    private final JdbcTemplate jdbc;

    public CodeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 그룹 ──

    public List<CodeGroup> groups() {
        return jdbc.query("SELECT * FROM CM_CODE_GROUP ORDER BY SYSTEM_YN DESC, GROUP_CODE", GROUP);
    }

    public CodeGroup group(String groupCode) {
        return jdbc.query("SELECT * FROM CM_CODE_GROUP WHERE GROUP_CODE = ?", GROUP, groupCode).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("코드 그룹"));
    }

    @Transactional
    public CodeGroup createGroup(CodeGroup g, String userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CM_CODE_GROUP WHERE GROUP_CODE = ?", Integer.class,
                g.groupCode());
        if (n != null && n > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 있는 그룹 코드입니다: " + g.groupCode());
        }
        jdbc.update("INSERT INTO CM_CODE_GROUP (GROUP_CODE, NAME, DESCRIPTION, SYSTEM_YN, CREATED_AT, CREATED_BY)"
                + " VALUES (?, ?, ?, FALSE, ?, ?)", g.groupCode(), g.name(), g.description(), Times.now(), userId);
        return group(g.groupCode());
    }

    @Transactional
    public CodeGroup updateGroup(String groupCode, CodeGroup g, String userId) {
        group(groupCode);
        jdbc.update("UPDATE CM_CODE_GROUP SET NAME = ?, DESCRIPTION = ?, UPDATED_AT = ?, UPDATED_BY = ?"
                + " WHERE GROUP_CODE = ?", g.name(), g.description(), Times.now(), userId, groupCode);
        return group(groupCode);
    }

    @Transactional
    public void deleteGroup(String groupCode) {
        CodeGroup g = group(groupCode);
        if (g.system()) {
            throw ApiException.badRequest("VALIDATION", "시스템 기준 코드 그룹은 삭제할 수 없습니다.");
        }
        jdbc.update("DELETE FROM CM_CODE WHERE GROUP_CODE = ?", groupCode);
        jdbc.update("DELETE FROM CM_CODE_GROUP WHERE GROUP_CODE = ?", groupCode);
    }

    // ── 코드 ──

    public List<Code> codes(String groupCode) {
        group(groupCode);
        return jdbc.query("SELECT * FROM CM_CODE WHERE GROUP_CODE = ? ORDER BY SORT_ORD, CODE", CODE, groupCode);
    }

    /** 일괄 저장: 목록에 없는 코드는 삭제 */
    @Transactional
    public List<Code> saveCodes(String groupCode, List<Code> codes) {
        group(groupCode);
        Set<String> seen = new HashSet<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            Code c = codes.get(i);
            if (c.code() == null || c.code().isBlank()) {
                errors.put("[" + i + "].code", "코드를 입력해주세요.");
            } else if (c.code().length() > 50) {
                errors.put("[" + i + "].code", "코드는 50자 이하입니다.");
            } else if (!seen.add(c.code())) {
                errors.put("[" + i + "].code", "중복된 코드입니다: " + c.code());
            }
            if (c.name() == null || c.name().isBlank()) {
                errors.put("[" + i + "].name", "코드명을 입력해주세요.");
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.", errors);
        }
        jdbc.update("DELETE FROM CM_CODE WHERE GROUP_CODE = ?", groupCode);
        jdbc.batchUpdate("INSERT INTO CM_CODE (GROUP_CODE, CODE, NAME, SORT_ORD, USE_YN, ATTR1, ATTR2)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                codes.stream().map(c -> new Object[] {groupCode, c.code(), c.name(), c.sortOrder(), c.useYn(),
                        c.attr1(), c.attr2()}).toList());
        return codes(groupCode);
    }

    /** 화면 콤보용: 사용 중인 코드만 */
    public Map<String, List<Code>> activeCodes(List<String> groups) {
        Map<String, List<Code>> out = new LinkedHashMap<>();
        for (String g : groups) {
            out.put(g, jdbc.query("SELECT * FROM CM_CODE WHERE GROUP_CODE = ? AND USE_YN = TRUE"
                    + " ORDER BY SORT_ORD, CODE", CODE, g));
        }
        return out;
    }

    /** 엑셀 가져오기용 upsert. [inserted, updated] */
    @Transactional
    public int[] upsertCodes(String groupCode, List<Code> codes) {
        group(groupCode);
        int ins = 0;
        int upd = 0;
        for (Code c : codes) {
            int n = jdbc.update("UPDATE CM_CODE SET NAME = ?, SORT_ORD = ?, USE_YN = ?, ATTR1 = ?, ATTR2 = ?"
                    + " WHERE GROUP_CODE = ? AND CODE = ?", c.name(), c.sortOrder(), c.useYn(), c.attr1(), c.attr2(),
                    groupCode, c.code());
            if (n == 0) {
                jdbc.update("INSERT INTO CM_CODE (GROUP_CODE, CODE, NAME, SORT_ORD, USE_YN, ATTR1, ATTR2)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)", groupCode, c.code(), c.name(), c.sortOrder(), c.useYn(),
                        c.attr1(), c.attr2());
                ins++;
            } else {
                upd++;
            }
        }
        return new int[] {ins, upd};
    }

    public boolean codeExists(String groupCode, String code) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CM_CODE WHERE GROUP_CODE = ? AND CODE = ?",
                Integer.class, groupCode, code);
        return n != null && n > 0;
    }

    // ── 용어 ──

    public PageDto<Term> terms(String q, Integer page, Integer size) {
        int[] ps = PageDto.normalize(page, size);
        String where = "";
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where = " WHERE LOWER(TERM) LIKE ? OR LOWER(COALESCE(ABBR, '')) LIKE ? OR LOWER(DEFINITION) LIKE ?";
            String like = "%" + q.toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM CM_TERM" + where, Long.class, args.toArray());
        args.add(ps[1]);
        args.add((long) ps[0] * ps[1]);
        List<Term> content = jdbc.query("SELECT * FROM CM_TERM" + where + " ORDER BY TERM LIMIT ? OFFSET ?", TERM,
                args.toArray());
        return new PageDto<>(content, ps[0], ps[1], total == null ? 0 : total);
    }

    public List<Term> allTerms() {
        return jdbc.query("SELECT * FROM CM_TERM ORDER BY TERM", TERM);
    }

    public Term term(String termId) {
        return jdbc.query("SELECT * FROM CM_TERM WHERE TERM_ID = ?", TERM, termId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("용어"));
    }

    @Transactional
    public Term createTerm(Term t, String userId) {
        String id = Ulid.next();
        jdbc.update("INSERT INTO CM_TERM (TERM_ID, TERM, ABBR, DEFINITION, TERM_DOMAIN, CREATED_AT, CREATED_BY)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)", id, t.term(), t.abbr(), t.definition(), t.domain(), Times.now(),
                userId);
        return term(id);
    }

    @Transactional
    public Term updateTerm(String termId, Term t, String userId) {
        term(termId);
        jdbc.update("UPDATE CM_TERM SET TERM = ?, ABBR = ?, DEFINITION = ?, TERM_DOMAIN = ?, UPDATED_AT = ?,"
                + " UPDATED_BY = ? WHERE TERM_ID = ?", t.term(), t.abbr(), t.definition(), t.domain(), Times.now(),
                userId, termId);
        return term(termId);
    }

    @Transactional
    public void deleteTerm(String termId) {
        term(termId);
        jdbc.update("DELETE FROM CM_TERM WHERE TERM_ID = ?", termId);
    }

    /** 엑셀 가져오기: 같은 용어명이면 갱신. [inserted, updated] */
    @Transactional
    public int[] upsertTerms(List<Term> terms, String userId) {
        int ins = 0;
        int upd = 0;
        for (Term t : terms) {
            int n = jdbc.update("UPDATE CM_TERM SET ABBR = ?, DEFINITION = ?, TERM_DOMAIN = ?, UPDATED_AT = ?,"
                    + " UPDATED_BY = ? WHERE TERM = ?", t.abbr(), t.definition(), t.domain(), Times.now(), userId,
                    t.term());
            if (n == 0) {
                createTerm(t, userId);
                ins++;
            } else {
                upd++;
            }
        }
        return new int[] {ins, upd};
    }
}
