package kr.pmsx.as;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import kr.pmsx.as.viewer.GraphReader;
import kr.pmsx.as.viewer.SystemStructure;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 시스템 단위 "요소(또는 화면 로드) → 테이블 CRUD" 색인. 시스템 CRUD 매트릭스, 테이블별 사용 화면 수,
 * 산출물(CRUD 매트릭스)에 쓴다. 활성 엣지 전체를 정수 인덱스 그래프로 한 번 읽고 노드별 도달 테이블을 메모이즈한다.
 * 같은 핸들러를 쓰는 요소들은 도달 결과(맵 객체)를 공유해 메모리를 아낀다. 그래프가 바뀌면(GraphChangedEvent) 버린다.
 */
@Component
public class UsageIndex {

    public static final class Index {
        final List<String> keys;
        /** 시작 키(el:/scr:) → 테이블 노드 인덱스 → CRUD 비트 (공유 객체일 수 있으므로 읽기 전용) */
        final Map<String, Map<Integer, Integer>> byStart = new HashMap<>();
        final Map<String, BitSet> screensByTable = new HashMap<>();
        /** 화면 키 → (그 화면 요소들 + 화면 로드) 도달 테이블 CRUD 합 — 시스템 CRUD(화면 축) 빠른 경로 */
        final Map<String, Map<Integer, Integer>> byScreen = new HashMap<>();

        Index(List<String> keys) {
            this.keys = keys;
        }

        /** 시작 키의 테이블 키 → CRUD 문자열 */
        public Map<String, String> tablesOf(String startKey) {
            Map<String, String> out = new HashMap<>();
            byStart.getOrDefault(startKey, Map.of()).forEach((t, c) -> out.put(keys.get(t), crud(c)));
            return out;
        }

        /** 모든 시작 키에 대해 (시작 키, 테이블 키 → CRUD 비트) */
        public void forEachStart(BiConsumer<String, Map<Integer, Integer>> f) {
            byStart.forEach(f);
        }

        public String tableKey(int idx) {
            return keys.get(idx);
        }

        /** 화면 단위 CRUD 합 (테이블 인덱스 → CRUD 비트). 없으면 빈 맵 */
        public Map<Integer, Integer> screenCrud(String screenKey) {
            return byScreen.getOrDefault(screenKey, Map.of());
        }

        /** 테이블을 쓰는 화면 수 (요소 → 그 화면, 화면 로드 → 그 화면) */
        public int screenCount(String tableKey) {
            BitSet b = screensByTable.get(tableKey);
            return b == null ? 0 : b.cardinality();
        }
    }

    private static final int CRUD_C = 1;
    private static final int CRUD_R = 2;
    private static final int CRUD_U = 4;
    private static final int CRUD_D = 8;
    private static final Set<String> FLOW = Set.copyOf(GraphReader.FLOW_KINDS);

    private final JdbcTemplate jdbc;
    private final Map<String, Index> cache = new ConcurrentHashMap<>();

    public UsageIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    public void onChange(GraphChangedEvent e) {
        cache.remove(e.systemId());
    }

    public Index get(String systemId) {
        return cache.computeIfAbsent(systemId, this::build);
    }

    // ───────────────────────── 구성 ─────────────────────────

    private static final class Builder {
        final Map<String, Integer> ids = new HashMap<>();
        final List<String> keys = new ArrayList<>();
        int[] head = new int[1024];
        int[] next = new int[4096];
        int[] to = new int[4096];
        byte[] kind = new byte[4096];       // 0 = event/기타(첫 단계만), 1 = flow
        byte[] crud = new byte[4096];
        int edges;
        boolean[] common = new boolean[0];

        Builder() {
            Arrays.fill(head, -1);
        }

        int id(String key) {
            Integer v = ids.get(key);
            if (v != null) {
                return v;
            }
            int n = keys.size();
            ids.put(key, n);
            keys.add(key);
            if (n >= head.length) {
                int old = head.length;
                head = Arrays.copyOf(head, old * 2);
                Arrays.fill(head, old, head.length, -1);
            }
            return n;
        }

        void edge(String from, String toKey, String k, String c) {
            int f = id(from);
            int t = id(toKey);
            if (edges >= to.length) {
                int n = to.length * 2;
                next = Arrays.copyOf(next, n);
                to = Arrays.copyOf(to, n);
                kind = Arrays.copyOf(kind, n);
                crud = Arrays.copyOf(crud, n);
            }
            to[edges] = t;
            kind[edges] = (byte) (FLOW.contains(k) ? 1 : 0);
            crud[edges] = "accesses".equals(k) ? (byte) bits(c) : 0;
            next[edges] = head[f];
            head[f] = edges++;
        }
    }

    private Index build(String systemId) {
        String latest = SystemStructure.latestRunId(jdbc, systemId);
        Builder b = new Builder();
        List<Object> args = new ArrayList<>(List.of(systemId));
        String run = "";
        if (latest != null) {
            run = " AND (LAST_RUN_ID = ? OR SOURCE = 'manual' OR EDITED = TRUE OR CONFIRMED = TRUE OR LAST_RUN_ID IS NULL)";
            args.add(latest);
        }
        // ORDER BY 없이 스트리밍 (연결선 100만 건 대비)
        jdbc.query("SELECT FROM_KEY, TO_KEY, KIND, CRUD FROM AS_EDGE WHERE SYSTEM_ID = ? AND STATUS = 'active'"
                + " AND KIND <> 'binds'" + run, rs -> {
                    b.edge(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
                }, args.toArray());
        b.common = new boolean[b.keys.size()];
        jdbc.query("SELECT NODE_KEY FROM AS_NODE WHERE SYSTEM_ID = ? AND COMMON = TRUE", rs -> {
            Integer i = b.ids.get(rs.getString(1));
            if (i != null) {
                b.common[i] = true;
            }
        }, systemId);
        // 시작 키 → 화면 번호 (BitSet 위치)
        Map<String, Integer> screenNo = new HashMap<>();
        Map<String, Integer> startScreen = new HashMap<>();
        Map<String, String> startScreenKey = new HashMap<>();
        jdbc.query("SELECT SCREEN_KEY FROM AS_SCREEN WHERE SYSTEM_ID = ?", rs -> {
            String k = rs.getString(1);
            int n = screenNo.size();
            screenNo.put(k, n);
            startScreen.put(k, n);
            startScreenKey.put(k, k);
        }, systemId);
        // 화면 CRUD 매트릭스와 같은 기준: 숨김 제외, 수동 또는 화면의 최신 회차 요소
        jdbc.query("SELECT E.ELEMENT_KEY, S.SCREEN_KEY FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID"
                + " WHERE E.SYSTEM_ID = ? AND E.HIDDEN = FALSE AND (E.MANUAL_YN = 'Y' OR E.LAST_RUN_ID = S.LAST_RUN_ID)",
                rs -> {
                    Integer n = screenNo.get(rs.getString(2));
                    if (n != null && b.ids.containsKey(rs.getString(1))) {
                        startScreen.put(rs.getString(1), n);
                        startScreenKey.put(rs.getString(1), rs.getString(2));
                    }
                }, systemId);

        Map<Integer, Map<Integer, Integer>> memo = new HashMap<>();
        Set<Integer> inProgress = new HashSet<>();
        Index idx = new Index(b.keys);
        Map<Integer, BitSet> screensByTable = new HashMap<>();
        for (int s = 0; s < b.keys.size(); s++) {
            String key = b.keys.get(s);
            if (b.head[s] < 0 || !(key.startsWith("el:") || key.startsWith("scr:"))) {
                continue;
            }
            Map<Integer, Integer> acc = null;
            boolean shared = false;
            for (int e = b.head[s]; e >= 0; e = b.next[e]) {
                // 화면 키는 navigates 로 다른 화면을 가리킬 수 있으므로 시작점에서도 화면은 따라가지 않는다
                Map<Integer, Integer> child = b.keys.get(b.to[e]).startsWith("scr:") ? Map.of()
                        : reach(b, b.to[e], memo, inProgress, 1);
                if (b.crud[e] == 0 && acc == null) {
                    acc = child;              // 첫 결과는 공유 (복사하지 않음)
                    shared = true;
                    continue;
                }
                if (shared || acc == null) {
                    acc = new HashMap<>(acc == null ? Map.of() : acc);
                    shared = false;
                }
                if (b.crud[e] != 0) {
                    acc.merge(b.to[e], (int) b.crud[e], (x, y) -> x | y);
                }
                Map<Integer, Integer> target = acc;
                child.forEach((t, c) -> target.merge(t, c, (x, y) -> x | y));
            }
            if (acc == null || acc.isEmpty()) {
                continue;
            }
            idx.byStart.put(key, acc);
            String sk = startScreenKey.get(key);
            if (sk != null) {
                Map<Integer, Integer> agg = idx.byScreen.computeIfAbsent(sk, k -> new HashMap<>());
                acc.forEach((t, c) -> agg.merge(t, c, (x, y) -> x | y));
            }
            Integer screen = startScreen.get(key);
            if (screen != null) {
                for (Integer t : acc.keySet()) {
                    screensByTable.computeIfAbsent(t, k -> new BitSet()).set(screen);
                }
            }
        }
        screensByTable.forEach((t, bs) -> idx.screensByTable.put(b.keys.get(t), bs));
        return idx;
    }

    /** 노드에서 FLOW 엣지로 도달하는 테이블과 CRUD (메모이즈, 순환은 진행 중 노드를 빈 결과로 끊는다). */
    private Map<Integer, Integer> reach(Builder b, int n, Map<Integer, Map<Integer, Integer>> memo,
            Set<Integer> inProgress, int depth) {
        Map<Integer, Integer> m = memo.get(n);
        if (m != null) {
            return m;
        }
        if (b.common[n] || b.keys.get(n).startsWith("scr:") || depth > 12 || !inProgress.add(n)) {
            return Map.of();
        }
        Map<Integer, Integer> acc = new HashMap<>();
        for (int e = b.head[n]; e >= 0; e = b.next[e]) {
            if (b.kind[e] == 0) {
                continue;
            }
            if (b.crud[e] != 0) {
                acc.merge(b.to[e], (int) b.crud[e], (x, y) -> x | y);
            }
            reach(b, b.to[e], memo, inProgress, depth + 1).forEach((t, c) -> acc.merge(t, c, (x, y) -> x | y));
        }
        inProgress.remove(n);
        memo.put(n, acc);
        return acc;
    }

    static int bits(String crud) {
        int v = 0;
        if (crud != null) {
            for (char c : crud.toCharArray()) {
                v |= switch (c) {
                    case 'C' -> CRUD_C;
                    case 'R' -> CRUD_R;
                    case 'U' -> CRUD_U;
                    case 'D' -> CRUD_D;
                    default -> 0;
                };
            }
        }
        return v;
    }

    public static String crud(int v) {
        StringBuilder sb = new StringBuilder();
        if ((v & CRUD_C) != 0) {
            sb.append('C');
        }
        if ((v & CRUD_R) != 0) {
            sb.append('R');
        }
        if ((v & CRUD_U) != 0) {
            sb.append('U');
        }
        if ((v & CRUD_D) != 0) {
            sb.append('D');
        }
        return sb.toString();
    }

    /** 두 CRUD 문자열 합치기 */
    public static String merge(String a, String b) {
        return crud(bits(a) | bits(b));
    }
}
