package kr.pmsx.as;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.pmsx.cm.excel.ExcelDataset;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** As-Is 엑셀 데이터셋: as-screens(화면 목록), as-elements(화면 컴포넌트 목록) — docs/10 §3 */
@Configuration
public class AsDatasets {

    @Bean
    ExcelDataset asScreensDataset(JdbcTemplate jdbc, ProjectAccess access) {
        return new ExcelDataset() {
            public String id() {
                return "as-screens";
            }

            public String name() {
                return "화면목록";
            }

            public List<String> params() {
                return List.of("systemId");
            }

            public List<Column> columns() {
                return List.of(Column.of("screenCode", "화면ID"), Column.of("name", "화면명"), Column.of("kind", "유형"),
                        Column.of("uiTech", "화면기술"), Column.of("sourceFile", "소스"),
                        Column.of("status", "분석상태"), Column.of("owner", "담당자"),
                        Column.number("elementCount", "요소수"), Column.of("collectedSeq", "수집회차"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                access.forSystem(user, ExcelDataset.required(params, "systemId"));
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                String systemId = ExcelDataset.required(params, "systemId");
                return jdbc.query("""
                        SELECT S.SCREEN_CODE, S.NAME, S.KIND, S.UI_TECH, S.SOURCE_FILE, A.STATUS, U.NAME AS OWNER_NM,
                               (SELECT COUNT(*) FROM AS_ELEMENT E WHERE E.SCREEN_ID = S.SCREEN_ID) AS EL_CNT,
                               R.SEQ
                          FROM AS_SCREEN S
                          LEFT JOIN AS_ANALYSIS A ON A.SCREEN_ID = S.SCREEN_ID
                          LEFT JOIN CM_USER U ON U.USER_ID = A.OWNER_ID
                          LEFT JOIN AS_COLLECT_RUN R ON R.RUN_ID = S.LAST_RUN_ID
                         WHERE S.SYSTEM_ID = ?
                         ORDER BY S.SCREEN_CODE""", (rs, i) -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("screenCode", rs.getString("SCREEN_CODE"));
                            m.put("name", rs.getString("NAME"));
                            m.put("kind", rs.getString("KIND"));
                            m.put("uiTech", rs.getString("UI_TECH"));
                            m.put("sourceFile", rs.getString("SOURCE_FILE"));
                            m.put("status", rs.getString("STATUS"));
                            m.put("owner", rs.getString("OWNER_NM"));
                            m.put("elementCount", rs.getInt("EL_CNT"));
                            m.put("collectedSeq", rs.getObject("SEQ") == null ? null : rs.getInt("SEQ"));
                            return m;
                        }, systemId);
            }
        };
    }

    @Bean
    ExcelDataset asElementsDataset(JdbcTemplate jdbc, ProjectAccess access) {
        return new ExcelDataset() {
            public String id() {
                return "as-elements";
            }

            public String name() {
                return "화면컴포넌트";
            }

            public List<String> params() {
                return List.of("systemId", "screenId");
            }

            public List<Column> columns() {
                return List.of(Column.of("screenCode", "화면ID"), Column.of("screenName", "화면명"),
                        Column.number("seqNo", "번호"), Column.of("elementCode", "요소ID"), Column.of("label", "라벨"),
                        Column.code("type", "유형", false, "ELEMENT_TYPE"), Column.of("binding", "바인딩"),
                        Column.of("events", "이벤트"), Column.of("matched", "매칭"), Column.of("danger", "위험버튼"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                access.forSystem(user, ExcelDataset.required(params, "systemId"));
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                String systemId = ExcelDataset.required(params, "systemId");
                String screenId = params.get("screenId");
                Map<String, List<String>> events = new HashMap<>();
                jdbc.query("""
                        SELECT E.FROM_KEY, E.EVENT, N.NAME FROM AS_EDGE E
                          LEFT JOIN AS_NODE N ON N.SYSTEM_ID = E.SYSTEM_ID AND N.NODE_KEY = E.TO_KEY
                         WHERE E.SYSTEM_ID = ? AND E.KIND = 'event' AND E.STATUS = 'active'
                         ORDER BY E.FROM_KEY, E.ORD, E.MODEL_SEQ""", rs -> {
                            events.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                    .add(rs.getString(2) + " → " + (rs.getString(3) == null ? "?" : rs.getString(3)));
                        }, systemId);
                List<Object> args = new ArrayList<>(List.of(systemId));
                String extra = "";
                if (screenId != null && !screenId.isBlank()) {
                    extra = " AND S.SCREEN_ID = ?";
                    args.add(screenId);
                }
                return jdbc.query("""
                        SELECT S.SCREEN_CODE, S.NAME AS SCREEN_NM, E.SEQ_NO, E.ELEMENT_KEY, E.ELEMENT_CODE, E.LABEL,
                               E.TYPE, E.BINDING, E.MATCHED, E.DANGER
                          FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID
                         WHERE E.SYSTEM_ID = ?""" + extra + " ORDER BY S.SCREEN_CODE, E.SEQ_NO", (rs, i) -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("screenCode", rs.getString("SCREEN_CODE"));
                            m.put("screenName", rs.getString("SCREEN_NM"));
                            m.put("seqNo", rs.getInt("SEQ_NO"));
                            m.put("elementCode", rs.getString("ELEMENT_CODE"));
                            m.put("label", rs.getString("LABEL"));
                            m.put("type", rs.getString("TYPE"));
                            m.put("binding", rs.getString("BINDING"));
                            m.put("events", String.join("\n",
                                    events.getOrDefault(rs.getString("ELEMENT_KEY"), List.of())));
                            m.put("matched", rs.getString("MATCHED"));
                            m.put("danger", rs.getBoolean("DANGER"));
                            return m;
                        }, args.toArray());
            }
        };
    }

    // ───── 산출물(11 §5) 고객사 템플릿 매핑용 데이터셋: datasetId = 산출물 type (11 v0.2) ─────

    @Bean
    ExcelDataset screenSpecDataset(kr.pmsx.as.deliverable.DeliverableData data) {
        return new ExcelDataset() {
            public String id() {
                return "screen-spec";
            }

            public String name() {
                return "화면정의서";
            }

            public List<String> params() {
                return List.of("systemId", "screenIds", "status");
            }

            public List<Column> columns() {
                return List.of(Column.of("screenCode", "화면ID"), Column.of("screenName", "화면명"),
                        Column.of("menuPath", "메뉴 경로"), Column.number("seqNo", "번호"), Column.of("label", "라벨"),
                        Column.of("elementCode", "요소ID"), Column.of("type", "유형"), Column.of("binding", "바인딩"),
                        Column.of("events", "이벤트"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                data.checkAccess(user, ExcelDataset.required(params, "systemId"));
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                return data.screenSpecRows(user, ExcelDataset.required(params, "systemId"), params.get("screenIds"),
                        params.get("status"));
            }
        };
    }

    @Bean
    ExcelDataset crudMatrixDataset(kr.pmsx.as.deliverable.DeliverableData data) {
        return new ExcelDataset() {
            public String id() {
                return "crud-matrix";
            }

            public String name() {
                return "CRUD매트릭스";
            }

            public List<String> params() {
                return List.of("systemId", "screenIds", "status");
            }

            public List<Column> columns() {
                return List.of(Column.of("screenCode", "화면ID"), Column.of("screenName", "화면명"),
                        Column.of("table", "테이블"), Column.of("crud", "CRUD"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                data.checkAccess(user, ExcelDataset.required(params, "systemId"));
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                return data.crudRows(user, ExcelDataset.required(params, "systemId"), params.get("screenIds"),
                        params.get("status"));
            }
        };
    }

    @Bean
    ExcelDataset asProgramSpecDataset(kr.pmsx.as.deliverable.DeliverableData data) {
        return new ExcelDataset() {
            public String id() {
                return "program-spec";
            }

            public String name() {
                return "프로그램사양서";
            }

            public List<String> params() {
                return List.of("systemId", "screenIds", "status");
            }

            public List<Column> columns() {
                return List.of(Column.of("screenCode", "화면ID"), Column.of("screenName", "화면명"),
                        Column.number("seqNo", "번호"), Column.of("element", "요소"), Column.of("event", "이벤트"),
                        Column.of("handler", "핸들러"), Column.of("apis", "API"),
                        Column.of("serverFunctions", "서버 함수"), Column.of("sqls", "SQL"),
                        Column.of("tables", "테이블(CRUD)"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                data.checkAccess(user, ExcelDataset.required(params, "systemId"));
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                return data.programRows(user, ExcelDataset.required(params, "systemId"), params.get("screenIds"),
                        params.get("status"));
            }
        };
    }

    @Bean
    ExcelDataset asTableSpecDataset(kr.pmsx.as.deliverable.DeliverableData data) {
        return new ExcelDataset() {
            public String id() {
                return "table-spec";
            }

            public String name() {
                return "테이블정의서";
            }

            public List<String> params() {
                return List.of("systemId");
            }

            public List<Column> columns() {
                return List.of(Column.of("table", "테이블"), Column.of("tableComment", "테이블 설명"),
                        Column.of("subjectArea", "주제영역"), Column.number("no", "순번"), Column.of("column", "컬럼"),
                        Column.of("dataType", "타입"), Column.of("nullable", "NULL"), Column.of("pk", "PK"),
                        Column.of("fk", "FK"), Column.of("default", "기본값"), Column.of("comment", "설명"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                data.checkAccess(user, ExcelDataset.required(params, "systemId"));
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                return data.tableColumnRows(user, ExcelDataset.required(params, "systemId"));
            }
        };
    }

    @Bean
    ExcelDataset asInterfaceListDataset(kr.pmsx.as.deliverable.DeliverableData data) {
        return new ExcelDataset() {
            public String id() {
                return "interface-list";
            }

            public String name() {
                return "인터페이스목록";
            }

            public List<String> params() {
                return List.of("systemId");
            }

            public List<Column> columns() {
                return List.of(Column.of("method", "메서드"), Column.of("path", "경로"),
                        Column.of("serverFunctions", "서버 함수"), Column.of("screens", "호출 화면"),
                        Column.number("screenCount", "화면 수"));
            }

            public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
                data.checkAccess(user, ExcelDataset.required(params, "systemId"));
            }

            public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
                return data.interfaceRows(user, ExcelDataset.required(params, "systemId"));
            }
        };
    }
}
