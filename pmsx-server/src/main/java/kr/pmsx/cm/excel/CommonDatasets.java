package kr.pmsx.cm.excel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.pmsx.cm.admin.UserAdminController;
import kr.pmsx.cm.code.CodeService;
import kr.pmsx.cm.code.CodeService.Code;
import kr.pmsx.cm.code.CodeService.Term;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.audit.AuditController;
import kr.pmsx.common.security.AuthUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 공통 영역 엑셀 데이터셋: codes, terms, users, audit-logs (docs/10 §3) */
@Configuration
public class CommonDatasets {

    @Bean
    ExcelDataset codesDataset(CodeService codes) {
        return new ExcelDataset() {
            public String id() {
                return "codes";
            }

            public String name() {
                return "공통코드";
            }

            public boolean importable() {
                return true;
            }

            public List<String> params() {
                return List.of("groupCode");
            }

            public List<Column> columns() {
                return List.of(Column.required("code", "코드"), Column.required("name", "코드명"),
                        Column.number("sortOrder", "정렬순서"), Column.of("useYn", "사용여부"),
                        Column.of("attr1", "속성1"), Column.of("attr2", "속성2"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                if (write && !user.isSysAdmin()) {
                    throw ApiException.forbidden();
                }
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                String group = ExcelDataset.required(params, "groupCode");
                List<Map<String, Object>> out = new ArrayList<>();
                for (Code c : codes.codes(group)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", c.code());
                    m.put("name", c.name());
                    m.put("sortOrder", c.sortOrder());
                    m.put("useYn", c.useYn());
                    m.put("attr1", c.attr1());
                    m.put("attr2", c.attr2());
                    out.add(m);
                }
                return out;
            }

            public Map<String, String> validateRow(Map<String, String> v, Map<String, String> params) {
                String yn = v.getOrDefault("useYn", "");
                if (!yn.isEmpty() && !yn.matches("(?i)Y|N|TRUE|FALSE")) {
                    return Map.of("useYn", "사용여부는 Y 또는 N 입니다.");
                }
                if (v.getOrDefault("code", "").length() > 50) {
                    return Map.of("code", "코드는 50자 이하입니다.");
                }
                return Map.of();
            }

            public int[] commit(AuthUser user, List<Map<String, String>> rows, Map<String, String> params) {
                String group = ExcelDataset.required(params, "groupCode");
                List<Code> list = rows.stream().map(r -> new Code(r.get("code"), r.get("name"),
                        r.getOrDefault("sortOrder", "").isEmpty() ? 0
                                : new java.math.BigDecimal(r.get("sortOrder").replace(",", "")).intValue(),
                        !r.getOrDefault("useYn", "Y").matches("(?i)N|FALSE"), blankToNull(r.get("attr1")),
                        blankToNull(r.get("attr2")))).toList();
                return codes.upsertCodes(group, list);
            }
        };
    }

    @Bean
    ExcelDataset termsDataset(CodeService codes) {
        return new ExcelDataset() {
            public String id() {
                return "terms";
            }

            public String name() {
                return "용어사전";
            }

            public boolean importable() {
                return true;
            }

            public List<Column> columns() {
                return List.of(Column.required("term", "용어"), Column.of("abbr", "약어"),
                        Column.required("definition", "정의"), Column.of("domain", "도메인"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                if (write && !user.isSysAdmin()) {
                    throw ApiException.forbidden();
                }
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Term t : codes.allTerms()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("term", t.term());
                    m.put("abbr", t.abbr());
                    m.put("definition", t.definition());
                    m.put("domain", t.domain());
                    out.add(m);
                }
                return out;
            }

            public int[] commit(AuthUser user, List<Map<String, String>> rows, Map<String, String> params) {
                return codes.upsertTerms(rows.stream().map(r -> new Term(null, r.get("term"),
                        blankToNull(r.get("abbr")), r.get("definition"), blankToNull(r.get("domain")))).toList(),
                        user.userId());
            }
        };
    }

    @Bean
    ExcelDataset usersDataset(JdbcTemplate jdbc, UserAdminController admin) {
        return new ExcelDataset() {
            public String id() {
                return "users";
            }

            public String name() {
                return "사용자";
            }

            public List<Column> columns() {
                return List.of(Column.of("loginId", "아이디"), Column.of("name", "이름"), Column.of("email", "이메일"),
                        Column.code("orgType", "소속", false, "ORG_TYPE"), Column.of("authType", "인증방식"),
                        Column.of("roles", "시스템역할"), Column.of("locked", "잠금"),
                        Column.of("lastLoginAt", "최근로그인"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                if (!user.isSysAdmin()) {
                    throw ApiException.forbidden();
                }
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (String id : jdbc.queryForList("SELECT USER_ID FROM CM_USER ORDER BY LOGIN_ID", String.class)) {
                    UserAdminController.AdminUser u = admin.get(id);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("loginId", u.loginId());
                    m.put("name", u.name());
                    m.put("email", u.email());
                    m.put("orgType", u.orgType());
                    m.put("authType", u.authType());
                    m.put("roles", String.join(",", u.roles()));
                    m.put("locked", u.locked());
                    m.put("lastLoginAt", u.lastLoginAt());
                    out.add(m);
                }
                return out;
            }
        };
    }

    @Bean
    ExcelDataset auditLogsDataset(AuditController audit) {
        return new ExcelDataset() {
            public String id() {
                return "audit-logs";
            }

            public String name() {
                return "감사로그";
            }

            public List<String> params() {
                return List.of("from", "to", "userId", "action", "q");
            }

            public List<Column> columns() {
                return List.of(Column.of("at", "일시"), Column.of("loginId", "아이디"), Column.of("action", "행위"),
                        Column.of("target", "대상"), Column.of("detail", "내용"), Column.of("ip", "IP"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                // 조회 권한은 AuditController 조건과 같음 (SYS_ADMIN·PMO·PM)
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (AuditController.AuditLogDto l : audit.listAll(user, params.get("from"),
                        params.get("to"), params.get("userId"), params.get("action"), params.get("q"))) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("at", l.at());
                    m.put("loginId", l.loginId());
                    m.put("action", l.action());
                    m.put("target", l.target());
                    m.put("detail", l.detail());
                    m.put("ip", l.ip());
                    out.add(m);
                }
                return out;
            }
        };
    }

    static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

}
