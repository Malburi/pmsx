package kr.pmsx.common.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 서버 SQL(PostgreSQL·H2 공용 문법)을 Oracle·Tibero·SQL Server 문법으로 번역한다.
 *
 * <p>문자열 리터럴·따옴표 식별자·주석은 건드리지 않고 토큰 단위로 바꾼다. 처리 항목:
 * <ul>
 *   <li>TRUE/FALSE → 1/0 (불리언 컬럼은 NUMBER(1)/BIT)</li>
 *   <li>페이징: {@code LIMIT n [OFFSET m]}, {@code FETCH FIRST n ROWS ONLY}
 *       → Oracle 12c {@code OFFSET … FETCH NEXT}, SQL Server {@code OFFSET … FETCH NEXT}(ORDER BY 없으면 보충),
 *       Oracle 11g·Tibero {@code ROWNUM} 감싸기. 파라미터 순서가 바뀌면 {@link Translation#paramMap()} 으로 재배치</li>
 *   <li>Oracle 계열: FROM 없는 SELECT → FROM DUAL, SUBSTRING → SUBSTR, CAST(… AS VARCHAR(n)) → VARCHAR2(n)</li>
 *   <li>SQL Server: {@code ||} → {@code +}, CHR → CHAR, LENGTH → LEN, SUBSTR/2인자 SUBSTRING 보정,
 *       NULLS FIRST/LAST → CASE 정렬 키, 한글 리터럴 N 접두, CAST 타입(NVARCHAR·DATETIMEOFFSET)</li>
 *   <li>WITH RECURSIVE → WITH</li>
 * </ul>
 * 번역할 수 없는 구문은 그대로 두며, 벤더별 통합 테스트(H2 Oracle/MSSQLServer 모드, 실 SQL Server 재생)로 검증한다.
 */
public final class SqlTranslator {

    /** 번역 결과. paramMap[j] = 번역 SQL 의 (j+1)번째 '?' 가 받을 원래 파라미터 번호(1부터). null 이면 순서 동일. */
    public record Translation(String sql, int[] paramMap, int originalParamCount) {
        public boolean remapped() {
            return paramMap != null;
        }
    }

    enum Kind { WS, COMMENT, STRING, QIDENT, WORD, NUMBER, PARAM, SYMBOL }

    record Tok(Kind kind, String text, int param) {
        Tok(Kind kind, String text) {
            this(kind, text, 0);
        }

        boolean word(String w) {
            return kind == Kind.WORD && text.equalsIgnoreCase(w);
        }

        boolean sym(String s) {
            return kind == Kind.SYMBOL && text.equals(s);
        }

        boolean sig() {
            return kind != Kind.WS && kind != Kind.COMMENT;
        }
    }

    private static final Set<String> DDL_HEADS = Set.of("CREATE", "ALTER", "DROP", "GRANT", "REVOKE", "DECLARE", "BEGIN",
            "EXEC", "EXECUTE", "IF", "TRUNCATE", "COMMENT", "SET", "USE", "CALL");

    private final DbVendor vendor;
    private final boolean legacyPaging;
    private final boolean emulated;

    public SqlTranslator(DbVendor vendor, boolean legacyPaging) {
        this(vendor, legacyPaging, false);
    }

    /** emulated: H2 호환 모드 테스트 — H2 에 없는 타입(DATETIMEOFFSET)으로는 바꾸지 않는다 */
    public SqlTranslator(DbVendor vendor, boolean legacyPaging, boolean emulated) {
        this.vendor = vendor;
        this.legacyPaging = legacyPaging && vendor.oracleLike();
        this.emulated = emulated;
    }

    public Translation translate(String sql) {
        List<Tok> toks = tokenize(sql);
        int params = (int) toks.stream().filter(t -> t.kind == Kind.PARAM).count();
        if (!vendor.needsTranslation()) {
            return new Translation(sql, null, params);
        }
        Tok head = firstSig(toks);
        if (head != null && head.kind == Kind.WORD && DDL_HEADS.contains(head.text.toUpperCase(Locale.ROOT))) {
            return new Translation(sql, null, params);
        }
        toks = booleanLiterals(toks);
        toks = dropRecursive(toks);
        toks = castTypes(toks);
        if (vendor == DbVendor.MSSQL) {
            toks = mssqlFunctions(toks);
            toks = mssqlNulls(toks);
            toks = mssqlStrings(toks);
        } else {
            toks = oracleFunctions(toks);
            toks = fromDual(toks);
        }
        toks = paging(toks);

        StringBuilder sb = new StringBuilder(sql.length() + 32);
        List<Integer> order = new ArrayList<>();
        for (Tok t : toks) {
            sb.append(t.text);
            if (t.kind == Kind.PARAM) {
                order.add(t.param);
            }
        }
        boolean identity = order.size() == params;
        for (int i = 0; identity && i < order.size(); i++) {
            identity = order.get(i) == i + 1;
        }
        int[] map = identity ? null : order.stream().mapToInt(Integer::intValue).toArray();
        return new Translation(sb.toString(), map, params);
    }

    /** '?' 자리에 리터럴을 넣은 SQL (검증·로그용). 문자열·주석 안의 '?' 는 건드리지 않는다. */
    public static String inline(String sql, Object[] params, java.util.function.Function<Object, String> literal) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Tok t : tokenize(sql)) {
            if (t.kind == Kind.PARAM) {
                sb.append(literal.apply(i < params.length ? params[i] : null));
                i++;
            } else {
                sb.append(t.text);
            }
        }
        return sb.toString();
    }

    // ───────────────────────── 토큰화 ─────────────────────────

    static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        int param = 0;
        while (i < n) {
            char c = s.charAt(i);
            int j;
            if (Character.isWhitespace(c)) {
                j = i;
                while (j < n && Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
                out.add(new Tok(Kind.WS, s.substring(i, j)));
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                j = s.indexOf('\n', i);
                j = j < 0 ? n : j;
                out.add(new Tok(Kind.COMMENT, s.substring(i, j)));
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                j = s.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                out.add(new Tok(Kind.COMMENT, s.substring(i, j)));
            } else if (c == '\'' || ((c == 'N' || c == 'n') && i + 1 < n && s.charAt(i + 1) == '\''
                    && (i == 0 || !isIdentPart(s.charAt(i - 1))))) {
                j = c == '\'' ? i + 1 : i + 2;
                while (j < n) {
                    if (s.charAt(j) == '\'') {
                        if (j + 1 < n && s.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                j = Math.min(n, j + 1);
                out.add(new Tok(Kind.STRING, s.substring(i, j)));
            } else if (c == '"') {
                j = s.indexOf('"', i + 1);
                j = j < 0 ? n : j + 1;
                out.add(new Tok(Kind.QIDENT, s.substring(i, j)));
            } else if (c == '?') {
                j = i + 1;
                out.add(new Tok(Kind.PARAM, "?", ++param));
            } else if (Character.isDigit(c)) {
                j = i;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(new Tok(Kind.NUMBER, s.substring(i, j)));
            } else if (isIdentStart(c)) {
                j = i;
                while (j < n && isIdentPart(s.charAt(j))) {
                    j++;
                }
                out.add(new Tok(Kind.WORD, s.substring(i, j)));
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (two.equals("||") || two.equals("<>") || two.equals("<=") || two.equals(">=") || two.equals("!=")
                        || two.equals("::")) {
                    j = i + 2;
                } else {
                    j = i + 1;
                }
                out.add(new Tok(Kind.SYMBOL, s.substring(i, j)));
            }
            i = j;
        }
        return out;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$' || c == '#' || c == '@';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
    }

    private static Tok firstSig(List<Tok> toks) {
        for (Tok t : toks) {
            if (t.sig()) {
                return t;
            }
        }
        return null;
    }

    /** 다음 의미 토큰 위치 (없으면 -1) */
    private static int nextSig(List<Tok> toks, int from) {
        for (int k = from; k < toks.size(); k++) {
            if (toks.get(k).sig()) {
                return k;
            }
        }
        return -1;
    }

    private static int prevSig(List<Tok> toks, int from) {
        for (int k = from; k >= 0; k--) {
            if (toks.get(k).sig()) {
                return k;
            }
        }
        return -1;
    }

    /** '(' 위치에 대응하는 ')' 위치 */
    private static int matchParen(List<Tok> toks, int open) {
        int depth = 0;
        for (int k = open; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.sym("(")) {
                depth++;
            } else if (t.sym(")")) {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        return -1;
    }

    private static List<Tok> parse(String s) {
        return tokenize(s);
    }

    // ───────────────────────── 공통 ─────────────────────────

    private static List<Tok> booleanLiterals(List<Tok> toks) {
        List<Tok> out = new ArrayList<>(toks.size());
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            int p = prevSig(toks, k - 1);
            boolean qualified = p >= 0 && toks.get(p).sym(".");
            if (!qualified && t.word("TRUE")) {
                out.add(new Tok(Kind.NUMBER, "1"));
            } else if (!qualified && t.word("FALSE")) {
                out.add(new Tok(Kind.NUMBER, "0"));
            } else {
                out.add(t);
            }
        }
        return out;
    }

    private static List<Tok> dropRecursive(List<Tok> toks) {
        List<Tok> out = new ArrayList<>(toks);
        for (int k = 0; k < out.size(); k++) {
            if (out.get(k).word("RECURSIVE")) {
                int p = prevSig(out, k - 1);
                if (p >= 0 && out.get(p).word("WITH")) {
                    out.remove(k);
                    if (k < out.size() && out.get(k).kind == Kind.WS) {
                        out.remove(k);
                    }
                }
            }
        }
        return out;
    }

    /** CAST(x AS <type>) 의 타입 이름 매핑 */
    private List<Tok> castTypes(List<Tok> toks) {
        List<Tok> out = new ArrayList<>(toks);
        for (int k = 0; k < out.size(); k++) {
            if (!out.get(k).word("CAST")) {
                continue;
            }
            int open = nextSig(out, k + 1);
            if (open < 0 || !out.get(open).sym("(")) {
                continue;
            }
            int close = matchParen(out, open);
            if (close < 0) {
                continue;
            }
            // 최상위(깊이 1) AS 찾기
            int depth = 0;
            int as = -1;
            for (int m = open; m < close; m++) {
                Tok t = out.get(m);
                if (t.sym("(")) {
                    depth++;
                } else if (t.sym(")")) {
                    depth--;
                } else if (depth == 1 && t.word("AS")) {
                    as = m;
                }
            }
            if (as < 0) {
                continue;
            }
            StringBuilder type = new StringBuilder();
            for (int m = as + 1; m < close; m++) {
                type.append(out.get(m).text);
            }
            String mapped = mapType(type.toString().trim());
            if (mapped != null) {
                List<Tok> repl = new ArrayList<>();
                repl.add(new Tok(Kind.WS, " "));
                repl.addAll(parse(mapped));
                for (int m = close - 1; m > as; m--) {
                    out.remove(m);
                }
                out.addAll(as + 1, repl);
            }
        }
        return out;
    }

    private String mapType(String type) {
        String t = type.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (vendor == DbVendor.MSSQL) {
            if (t.startsWith("VARCHAR")) {
                return "N" + type;
            }
            return switch (t) {
                case "TEXT" -> "NVARCHAR(MAX)";
                case "TIMESTAMP WITH TIME ZONE" -> emulated ? null : "DATETIMEOFFSET";
                case "BOOLEAN" -> "BIT";
                case "INTEGER" -> "INT";
                case "DOUBLE PRECISION" -> "FLOAT";
                default -> null;
            };
        }
        if (t.startsWith("VARCHAR(")) {
            return "VARCHAR2" + type.substring("VARCHAR".length());
        }
        return switch (t) {
            case "TEXT" -> "CLOB";
            case "BOOLEAN" -> "NUMBER(1)";
            case "DOUBLE PRECISION" -> "BINARY_DOUBLE";
            default -> null;
        };
    }

    // ───────────────────────── Oracle 계열 ─────────────────────────

    private static List<Tok> oracleFunctions(List<Tok> toks) {
        List<Tok> out = new ArrayList<>(toks);
        for (int k = 0; k < out.size(); k++) {
            Tok t = out.get(k);
            if (t.word("SUBSTRING")) {
                int open = nextSig(out, k + 1);
                if (open >= 0 && out.get(open).sym("(")) {
                    int close = matchParen(out, open);
                    boolean keywordForm = false;
                    for (int m = open; m < close; m++) {
                        keywordForm |= out.get(m).word("FROM") || out.get(m).word("FOR");
                    }
                    if (!keywordForm) {
                        out.set(k, new Tok(Kind.WORD, "SUBSTR"));
                    }
                }
            }
        }
        return out;
    }

    /** FROM 없는 최상위 SELECT 에 FROM DUAL 보충 */
    private static List<Tok> fromDual(List<Tok> toks) {
        Tok head = firstSig(toks);
        if (head == null || !head.word("SELECT")) {
            return toks;
        }
        int depth = 0;
        for (Tok t : toks) {
            if (t.sym("(")) {
                depth++;
            } else if (t.sym(")")) {
                depth--;
            } else if (depth == 0 && (t.word("FROM") || t.word("UNION"))) {
                return toks;
            }
        }
        List<Tok> out = new ArrayList<>(toks);
        int end = out.size();
        while (end > 0 && (!out.get(end - 1).sig() || out.get(end - 1).sym(";"))) {
            end--;
        }
        out.addAll(end, parse(" FROM DUAL"));
        return out;
    }

    // ───────────────────────── SQL Server ─────────────────────────

    private static List<Tok> mssqlFunctions(List<Tok> toks) {
        List<Tok> out = new ArrayList<>(toks);
        for (int k = 0; k < out.size(); k++) {
            Tok t = out.get(k);
            if (t.sym("||")) {
                out.set(k, new Tok(Kind.SYMBOL, "+"));
                continue;
            }
            if (t.kind != Kind.WORD) {
                continue;
            }
            int open = nextSig(out, k + 1);
            if (open < 0 || !out.get(open).sym("(")) {
                continue;
            }
            String w = t.text.toUpperCase(Locale.ROOT);
            switch (w) {
                case "CHR" -> out.set(k, new Tok(Kind.WORD, "CHAR"));
                case "LENGTH", "CHAR_LENGTH" -> out.set(k, new Tok(Kind.WORD, "LEN"));
                case "SUBSTR", "SUBSTRING" -> {
                    out.set(k, new Tok(Kind.WORD, "SUBSTRING"));
                    int close = matchParen(out, open);
                    int commas = 0;
                    int depth = 0;
                    for (int m = open; m < close; m++) {
                        if (out.get(m).sym("(")) {
                            depth++;
                        } else if (out.get(m).sym(")")) {
                            depth--;
                        } else if (depth == 1 && out.get(m).sym(",")) {
                            commas++;
                        }
                    }
                    if (commas == 1) {
                        out.addAll(close, parse(", 1000000"));
                    }
                }
                default -> {
                }
            }
        }
        return out;
    }

    /** ORDER BY x [ASC|DESC] NULLS FIRST|LAST → CASE WHEN x IS NULL THEN … END, x [ASC|DESC] */
    private static List<Tok> mssqlNulls(List<Tok> toks) {
        List<Tok> out = new ArrayList<>(toks);
        for (int k = 0; k < out.size(); k++) {
            if (!out.get(k).word("NULLS")) {
                continue;
            }
            int fl = nextSig(out, k + 1);
            if (fl < 0 || !(out.get(fl).word("FIRST") || out.get(fl).word("LAST"))) {
                continue;
            }
            boolean last = out.get(fl).word("LAST");
            // 항목 시작: 같은 깊이의 ',' 또는 BY 다음
            int depth = 0;
            int start = -1;
            for (int m = k - 1; m >= 0; m--) {
                Tok t = out.get(m);
                if (t.sym(")")) {
                    depth++;
                } else if (t.sym("(")) {
                    if (depth == 0) {
                        start = m + 1;
                        break;
                    }
                    depth--;
                } else if (depth == 0 && (t.sym(",") || t.word("BY"))) {
                    start = m + 1;
                    break;
                }
            }
            if (start < 0) {
                continue;
            }
            int exprEnd = prevSig(out, k - 1);     // 마지막 의미 토큰 (ASC/DESC 이거나 식의 끝)
            String dir = "";
            if (exprEnd >= 0 && (out.get(exprEnd).word("ASC") || out.get(exprEnd).word("DESC"))) {
                dir = " " + out.get(exprEnd).text;
                exprEnd = prevSig(out, exprEnd - 1);
            }
            List<Tok> expr = new ArrayList<>(out.subList(start, exprEnd + 1));
            while (!expr.isEmpty() && !expr.get(0).sig()) {
                expr.remove(0);
            }
            List<Tok> repl = new ArrayList<>();
            repl.add(new Tok(Kind.WS, " "));
            repl.addAll(parse("CASE WHEN "));
            repl.addAll(expr);
            repl.addAll(parse(last ? " IS NULL THEN 1 ELSE 0 END, " : " IS NULL THEN 0 ELSE 1 END, "));
            repl.addAll(expr);
            if (!dir.isEmpty()) {
                repl.addAll(parse(dir));
            }
            for (int m = fl; m >= start; m--) {
                out.remove(m);
            }
            out.addAll(start, repl);
            k = start + repl.size() - 1;
        }
        return out;
    }

    /** 한글 등 비 ASCII 리터럴은 N'…' 로 (DB 코드 페이지와 무관하게 유니코드 유지) */
    private static List<Tok> mssqlStrings(List<Tok> toks) {
        List<Tok> out = new ArrayList<>(toks.size());
        for (Tok t : toks) {
            if (t.kind == Kind.STRING && t.text.startsWith("'") && t.text.chars().anyMatch(ch -> ch > 127)) {
                out.add(new Tok(Kind.STRING, "N" + t.text));
            } else {
                out.add(t);
            }
        }
        return out;
    }

    // ───────────────────────── 페이징 ─────────────────────────

    private List<Tok> paging(List<Tok> toks) {
        // 의미 토큰 + 깊이
        int depth = 0;
        int clause = -1;
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.sym("(")) {
                depth++;
            } else if (t.sym(")")) {
                depth--;
            } else if (depth == 0 && clause < 0 && (t.word("LIMIT") || t.word("OFFSET") || t.word("FETCH"))) {
                clause = k;
            }
        }
        if (clause < 0) {
            return toks;
        }
        Tok limit = null;
        Tok offset = null;
        int k = clause;
        List<Integer> sig = new ArrayList<>();
        for (int m = clause; m < toks.size(); m++) {
            if (toks.get(m).sig() && !toks.get(m).sym(";")) {
                sig.add(m);
            }
        }
        int p = 0;
        try {
            if (toks.get(sig.get(p)).word("LIMIT")) {
                limit = toks.get(sig.get(++p));
                p++;
                if (p < sig.size() && toks.get(sig.get(p)).word("OFFSET")) {
                    offset = toks.get(sig.get(++p));
                    p++;
                }
            } else if (toks.get(sig.get(p)).word("OFFSET")) {
                offset = toks.get(sig.get(++p));
                p++;
                if (p < sig.size() && (toks.get(sig.get(p)).word("ROWS") || toks.get(sig.get(p)).word("ROW"))) {
                    p++;
                }
            }
            if (p < sig.size() && toks.get(sig.get(p)).word("FETCH")) {
                p++;    // FIRST|NEXT
                p++;
                limit = toks.get(sig.get(p++));
                p++;    // ROW|ROWS
                if (!toks.get(sig.get(p++)).word("ONLY")) {
                    return toks;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            return toks;
        }
        if (p != sig.size() || (limit != null && !isValue(limit)) || (offset != null && !isValue(offset))) {
            return toks;    // 끝이 아닌 곳의 LIMIT 등은 번역하지 않는다
        }
        List<Tok> head = new ArrayList<>(toks.subList(0, k));
        while (!head.isEmpty() && !head.get(head.size() - 1).sig()) {
            head.remove(head.size() - 1);
        }
        List<Tok> out = new ArrayList<>();
        if (legacyPaging) {
            if (offset == null) {
                out.addAll(parse("SELECT * FROM ("));
                out.addAll(head);
                out.addAll(parse(") WHERE ROWNUM <= "));
                out.add(limit);
            } else {
                out.addAll(parse("SELECT * FROM (SELECT q__.*, ROWNUM rn__ FROM ("));
                out.addAll(head);
                out.addAll(parse(") q__ WHERE ROWNUM <= "));
                out.add(offset);
                out.addAll(parse(" + "));
                if (limit == null) {
                    out.addAll(parse("2147483647"));
                } else {
                    out.add(limit);
                }
                out.addAll(parse(") WHERE rn__ > "));
                out.add(offset);
            }
            return out;
        }
        out.addAll(head);
        if (vendor == DbVendor.MSSQL && !hasTopLevelOrderBy(head)) {
            out.addAll(parse(" ORDER BY (SELECT NULL)"));
        }
        if (vendor == DbVendor.MSSQL || offset != null) {
            out.addAll(parse(" OFFSET "));
            if (offset == null) {
                out.addAll(parse("0"));
            } else {
                out.add(offset);
            }
            out.addAll(parse(" ROWS"));
            if (limit != null) {
                out.addAll(parse(" FETCH NEXT "));
                out.add(limit);
                out.addAll(parse(" ROWS ONLY"));
            }
        } else {
            out.addAll(parse(" FETCH FIRST "));
            out.add(limit);
            out.addAll(parse(" ROWS ONLY"));
        }
        return out;
    }

    private static boolean isValue(Tok t) {
        return t.kind == Kind.PARAM || t.kind == Kind.NUMBER;
    }

    private static boolean hasTopLevelOrderBy(List<Tok> toks) {
        int depth = 0;
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.sym("(")) {
                depth++;
            } else if (t.sym(")")) {
                depth--;
            } else if (depth == 0 && t.word("ORDER")) {
                int n = nextSig(toks, k + 1);
                if (n >= 0 && toks.get(n).word("BY")) {
                    return true;
                }
            }
        }
        return false;
    }
}
