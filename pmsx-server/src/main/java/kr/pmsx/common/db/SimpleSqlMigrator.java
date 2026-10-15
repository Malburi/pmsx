package kr.pmsx.common.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway 가 지원하지 않는 DB(Tibero)용 단순 마이그레이션 실행기.
 *
 * <p>classpath:db/migration/{folder}/V{n}__*.sql 을 버전 순으로 실행하고 PMSX_SCHEMA_HISTORY 에 기록한다.
 * 스크립트는 생성기(tools/ddl/convert_ddl.py)가 만든 '줄 끝 ;' 단위 문장만 있다고 가정한다(PL/SQL 블록 없음).
 * 문장 하나가 실패하면 기동을 중단하고, 같은 버전은 다시 실행되지 않도록 성공 시에만 기록한다.
 */
public class SimpleSqlMigrator {

    private static final Logger log = LoggerFactory.getLogger(SimpleSqlMigrator.class);
    private static final Pattern VERSION = Pattern.compile("V(\\d+)__(.+)\\.sql");
    static final String HISTORY = "PMSX_SCHEMA_HISTORY";

    private final DataSource dataSource;
    private final String folder;

    public SimpleSqlMigrator(DataSource dataSource, String folder) {
        this.dataSource = dataSource;
        this.folder = folder;
    }

    public int migrate() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ensureHistory(jdbc);
        Set<Integer> done = new HashSet<>(jdbc.queryForList("SELECT VERSION_NO FROM " + HISTORY, Integer.class));
        int applied = 0;
        for (Script s : scripts()) {
            if (done.contains(s.version())) {
                continue;
            }
            log.info("DB 마이그레이션 V{} ({}) 실행", s.version(), s.description());
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                String script = s.sql().replace("${tstz}", "DATETIMEOFFSET").replace("${bit}", "BIT");
                for (String sql : statements(script)) {
                    try {
                        st.execute(sql);
                    } catch (SQLException e) {
                        throw new IllegalStateException("마이그레이션 V" + s.version() + " 실패: " + e.getMessage()
                                + "\n문장: " + sql, e);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("마이그레이션 V" + s.version() + " 실패", e);
            }
            jdbc.update("INSERT INTO " + HISTORY + " (VERSION_NO, DESCRIPTION, INSTALLED_AT) VALUES (?, ?, ?)",
                    s.version(), s.description(), OffsetDateTime.now());
            applied++;
        }
        return applied;
    }

    private void ensureHistory(JdbcTemplate jdbc) {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + HISTORY, Integer.class);
        } catch (RuntimeException e) {
            String ddl = switch (folder) {
                case "oracle" -> "(VERSION_NO NUMBER(10) PRIMARY KEY, DESCRIPTION VARCHAR2(200), INSTALLED_AT TIMESTAMP WITH TIME ZONE NOT NULL)";
                case "sqlserver" -> "(VERSION_NO INT PRIMARY KEY, DESCRIPTION NVARCHAR(200), INSTALLED_AT DATETIMEOFFSET NOT NULL)";
                default -> "(VERSION_NO INTEGER PRIMARY KEY, DESCRIPTION VARCHAR(200), INSTALLED_AT TIMESTAMP WITH TIME ZONE NOT NULL)";
            };
            jdbc.execute("CREATE TABLE " + HISTORY + " " + ddl);
        }
    }

    record Script(int version, String description, String sql) {
    }

    List<Script> scripts() {
        try {
            Resource[] rs = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/" + folder + "/V*__*.sql");
            List<Script> out = new ArrayList<>();
            for (Resource r : rs) {
                Matcher m = VERSION.matcher(r.getFilename() == null ? "" : r.getFilename());
                if (m.matches()) {
                    out.add(new Script(Integer.parseInt(m.group(1)), m.group(2),
                            new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
            out.sort(Comparator.comparingInt(Script::version));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("마이그레이션 스크립트를 읽을 수 없습니다: " + folder, e);
        }
    }

    /** 줄 끝 ';' 기준으로 문장 분리, '--' 주석 제거 (문자열 안 '--' 는 보존) */
    static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : script.split("\\r?\\n")) {
            String code = stripComment(line);
            if (code.isBlank()) {
                continue;
            }
            cur.append(code).append('\n');
            if (code.stripTrailing().endsWith(";")) {
                String s = cur.toString().strip();
                out.add(s.substring(0, s.length() - 1));
                cur.setLength(0);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().strip());
        }
        return out;
    }

    private static String stripComment(String line) {
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'') {
                q = !q;
            } else if (!q && c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
