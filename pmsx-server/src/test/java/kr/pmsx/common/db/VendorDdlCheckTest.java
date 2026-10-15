package kr.pmsx.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 벤더 DDL 정적 검사 — 실 Oracle 이 없는 환경에서 Oracle 고유 제약을 미리 잡는다.
 * <ul>
 *   <li>식별자(테이블·컬럼·제약·인덱스) 30자 이내 (Oracle 11g·12.1)</li>
 *   <li>Oracle·SQL Server 예약어를 식별자로 쓰지 않음</li>
 *   <li>Oracle 인덱스 키 최대 길이 6398 바이트(8K 블록, AL32UTF8 CHAR 는 글자당 4바이트로 계산)</li>
 *   <li>common 과 벤더 스크립트의 테이블·컬럼 구성이 같음</li>
 * </ul>
 */
class VendorDdlCheckTest {

    static final Set<String> ORACLE_RESERVED = Set.of("ACCESS", "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC",
            "AUDIT", "BETWEEN", "BY", "CHAR", "CHECK", "CLUSTER", "COLUMN", "COMMENT", "COMPRESS", "CONNECT", "CREATE",
            "CURRENT", "DATE", "DECIMAL", "DEFAULT", "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "EXCLUSIVE",
            "EXISTS", "FILE", "FLOAT", "FOR", "FROM", "GRANT", "GROUP", "HAVING", "IDENTIFIED", "IMMEDIATE", "IN",
            "INCREMENT", "INDEX", "INITIAL", "INSERT", "INTEGER", "INTERSECT", "INTO", "IS", "LEVEL", "LIKE", "LOCK",
            "LONG", "MAXEXTENTS", "MINUS", "MLSLABEL", "MODE", "MODIFY", "NOAUDIT", "NOCOMPRESS", "NOT", "NOWAIT",
            "NULL", "NUMBER", "OF", "OFFLINE", "ON", "ONLINE", "OPTION", "OR", "ORDER", "PCTFREE", "PRIOR", "PUBLIC",
            "RAW", "RENAME", "RESOURCE", "REVOKE", "ROW", "ROWID", "ROWNUM", "ROWS", "SELECT", "SESSION", "SET",
            "SHARE", "SIZE", "SMALLINT", "START", "SUCCESSFUL", "SYNONYM", "SYSDATE", "TABLE", "THEN", "TO", "TRIGGER",
            "UID", "UNION", "UNIQUE", "UPDATE", "USER", "VALIDATE", "VALUES", "VARCHAR", "VARCHAR2", "VIEW",
            "WHENEVER", "WHERE", "WITH");

    static final Set<String> MSSQL_RESERVED = Set.of("ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC",
            "AUTHORIZATION", "BACKUP", "BEGIN", "BETWEEN", "BREAK", "BROWSE", "BULK", "BY", "CASCADE", "CASE", "CHECK",
            "CHECKPOINT", "CLOSE", "CLUSTERED", "COALESCE", "COLLATE", "COLUMN", "COMMIT", "COMPUTE", "CONSTRAINT",
            "CONTAINS", "CONTINUE", "CONVERT", "CREATE", "CROSS", "CURRENT", "CURSOR", "DATABASE", "DBCC",
            "DEALLOCATE", "DECLARE", "DEFAULT", "DELETE", "DENY", "DESC", "DISK", "DISTINCT", "DISTRIBUTED", "DOUBLE",
            "DROP", "DUMP", "ELSE", "END", "ERRLVL", "ESCAPE", "EXCEPT", "EXEC", "EXECUTE", "EXISTS", "EXIT",
            "EXTERNAL", "FETCH", "FILE", "FILLFACTOR", "FOR", "FOREIGN", "FREETEXT", "FROM", "FULL", "FUNCTION",
            "GOTO", "GRANT", "GROUP", "HAVING", "HOLDLOCK", "IDENTITY", "IF", "IN", "INDEX", "INNER", "INSERT",
            "INTERSECT", "INTO", "IS", "JOIN", "KEY", "KILL", "LEFT", "LIKE", "LINENO", "LOAD", "MERGE", "NATIONAL",
            "NOCHECK", "NONCLUSTERED", "NOT", "NULL", "NULLIF", "OF", "OFF", "OFFSETS", "ON", "OPEN", "OPTION", "OR",
            "ORDER", "OUTER", "OVER", "PERCENT", "PIVOT", "PLAN", "PRECISION", "PRIMARY", "PRINT", "PROC",
            "PROCEDURE", "PUBLIC", "RAISERROR", "READ", "READTEXT", "RECONFIGURE", "REFERENCES", "REPLICATION",
            "RESTORE", "RESTRICT", "RETURN", "REVERT", "REVOKE", "RIGHT", "ROLLBACK", "ROWCOUNT", "ROWGUIDCOL", "RULE",
            "SAVE", "SCHEMA", "SELECT", "SET", "SETUSER", "SHUTDOWN", "SOME", "STATISTICS", "TABLE", "TABLESAMPLE",
            "TEXTSIZE", "THEN", "TO", "TOP", "TRAN", "TRANSACTION", "TRIGGER", "TRUNCATE", "TSEQUAL", "UNION",
            "UNIQUE", "UNPIVOT", "UPDATE", "UPDATETEXT", "USE", "USER", "VALUES", "VARYING", "VIEW", "WAITFOR", "WHEN",
            "WHERE", "WHILE", "WITH", "WRITETEXT");

    private static final Pattern CREATE = Pattern.compile("CREATE TABLE (\\w+) \\((.*?)\\n\\);", Pattern.DOTALL);
    private static final Pattern ADD_ORACLE = Pattern.compile("ALTER TABLE (\\w+) ADD \\((\\w+) ([^;]*)\\);");
    private static final Pattern COLUMN = Pattern.compile("^\\s+(\\w+)\\s+(\\$\\{\\w+\\}|[A-Z0-9_]+(?:\\([^)]*\\))?(?: WITH TIME ZONE)?)");
    private static final Pattern NAMED = Pattern.compile("(?:CONSTRAINT|CREATE INDEX|CREATE UNIQUE INDEX) (\\w+)");
    private static final Pattern INDEX = Pattern.compile("CREATE (?:UNIQUE )?INDEX \\w+ ON (\\w+) \\(([^)]*)\\)");
    private static final Pattern KEYED = Pattern.compile("(?:UNIQUE|PRIMARY KEY) \\(([^)]*)\\)");

    static String read(String folder) throws IOException {
        StringBuilder sb = new StringBuilder();
        Resource[] rs = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/" + folder + "/V*.sql");
        java.util.Arrays.sort(rs, java.util.Comparator.comparing(Resource::getFilename));
        for (Resource r : rs) {
            sb.append(new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString().replaceAll("--[^\\n]*", "");
    }

    /** table → (column → type) */
    static Map<String, Map<String, String>> tables(String ddl, boolean oracle) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Matcher m = CREATE.matcher(ddl);
        while (m.find()) {
            Map<String, String> cols = new LinkedHashMap<>();
            for (String line : m.group(2).split("\\n")) {
                Matcher c = COLUMN.matcher(line);
                if (c.find() && !Set.of("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN").contains(c.group(1))) {
                    cols.put(c.group(1), c.group(2));
                }
            }
            out.put(m.group(1), cols);
        }
        Matcher a = (oracle ? ADD_ORACLE : Pattern.compile("ALTER TABLE (\\w+) ADD(?: COLUMN)? (\\w+) ([^;]*);"))
                .matcher(ddl);
        while (a.find()) {
            out.computeIfAbsent(a.group(1), k -> new LinkedHashMap<>()).put(a.group(2), a.group(3).split(" ")[0]);
        }
        return out;
    }

    @Test
    void oracleIdentifiersAndReservedWords() throws IOException {
        String ddl = read("oracle");
        List<String> problems = new ArrayList<>();
        Map<String, Map<String, String>> t = tables(ddl, true);
        assertThat(t).hasSizeGreaterThan(70);
        t.forEach((table, cols) -> {
            check(problems, table, "테이블");
            cols.keySet().forEach(c -> check(problems, table + "." + c, "컬럼"));
        });
        Matcher n = NAMED.matcher(ddl);
        while (n.find()) {
            check(problems, n.group(1), "제약·인덱스");
        }
        assertThat(problems).isEmpty();
    }

    private static void check(List<String> problems, String qualified, String what) {
        String name = qualified.contains(".") ? qualified.substring(qualified.indexOf('.') + 1) : qualified;
        if (name.length() > 30) {
            problems.add(what + " 30자 초과: " + qualified);
        }
        if (ORACLE_RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
            problems.add(what + " Oracle 예약어: " + qualified);
        }
        if (MSSQL_RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
            problems.add(what + " SQL Server 예약어: " + qualified);
        }
    }

    @Test
    void oracleIndexKeyLength() throws IOException {
        String ddl = read("oracle");
        Map<String, Map<String, String>> t = tables(ddl, true);
        List<String> problems = new ArrayList<>();
        Map<String, List<String>> keys = new HashMap<>();
        Matcher create = CREATE.matcher(ddl);
        while (create.find()) {
            Matcher k = KEYED.matcher(create.group(2));
            while (k.find()) {
                keys.put(create.group(1) + " " + k.group(1), List.of(k.group(1).split("\\s*,\\s*")));
            }
            for (String line : create.group(2).split("\\n")) {
                Matcher c = COLUMN.matcher(line);
                if (c.find() && (line.contains("PRIMARY KEY") || line.contains(" UNIQUE"))) {
                    keys.put(create.group(1) + " " + c.group(1), List.of(c.group(1)));
                }
            }
        }
        Matcher idx = INDEX.matcher(ddl);
        while (idx.find()) {
            keys.put(idx.group(1) + " " + idx.group(2), List.of(idx.group(2).split("\\s*,\\s*")));
        }
        keys.forEach((key, cols) -> {
            String table = key.split(" ")[0];
            int bytes = 0;
            for (String c : cols) {
                bytes += oracleBytes(t.getOrDefault(table, Map.of()).getOrDefault(c.trim(), "?"));
            }
            if (bytes > 6398) {
                problems.add(key + " = " + bytes + " bytes");
            }
        });
        assertThat(keys).hasSizeGreaterThan(50);
        assertThat(problems).isEmpty();
    }

    static int oracleBytes(String type) {
        Matcher m = Pattern.compile("VARCHAR2\\((\\d+)( CHAR)?\\)").matcher(type);
        if (m.find()) {
            return Integer.parseInt(m.group(1)) * (m.group(2) == null ? 1 : 4);
        }
        m = Pattern.compile("CHAR\\((\\d+)\\)").matcher(type);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        if (type.startsWith("TIMESTAMP")) {
            return 13;
        }
        if (type.startsWith("DATE")) {
            return 7;
        }
        return 22;      // NUMBER·FLOAT
    }

    @Test
    void vendorScriptsHaveSameTablesAndColumns() throws IOException {
        Map<String, Map<String, String>> common = tables(read("common"), false);
        Map<String, Map<String, String>> oracle = tables(read("oracle"), true);
        Map<String, Map<String, String>> mssql = tables(read("sqlserver"), false);
        assertThat(oracle.keySet()).containsExactlyInAnyOrderElementsOf(common.keySet());
        assertThat(mssql.keySet()).containsExactlyInAnyOrderElementsOf(common.keySet());
        common.forEach((table, cols) -> {
            assertThat(oracle.get(table).keySet()).as(table).containsExactlyInAnyOrderElementsOf(cols.keySet());
            assertThat(mssql.get(table).keySet()).as(table).containsExactlyInAnyOrderElementsOf(cols.keySet());
        });
    }
}
