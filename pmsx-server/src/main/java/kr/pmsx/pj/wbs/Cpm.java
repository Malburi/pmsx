package kr.pmsx.pj.wbs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CPM(임계 경로) 계산. 시간 단위는 영업일 인덱스이며 연속 시간으로 다룬다:
 * 작업은 start 일의 시작에 시작해 start + dur 시점(= 마지막 날의 끝)에 끝난다. 마일스톤은 dur 0.
 * 계획 시작일은 "이 날 이전에는 시작하지 않음" 제약으로 본다. 총 여유(LS - ES)가 0 이하인 작업이 임계 경로.
 */
public final class Cpm {

    public record Task(String id, int start, int dur) {
    }

    public record Dep(String pred, String succ, String type, int lag) {
    }

    public record Result(Map<String, Integer> es, Map<String, Integer> ef, Map<String, Integer> totalFloat,
                         Set<String> critical, int projectEnd) {
    }

    /** 선후행 순환이 있으면 던진다 */
    public static class CycleException extends RuntimeException {
        public CycleException(String message) {
            super(message);
        }
    }

    private Cpm() {
    }

    /** deps 중 tasks 에 없는 작업이 끼어 있으면 그 의존은 무시한다(일정 없는 작업 등). */
    public static Result compute(Collection<Task> tasks, Collection<Dep> deps) {
        Map<String, Task> byId = new LinkedHashMap<>();
        for (Task t : tasks) {
            byId.put(t.id(), t);
        }
        Map<String, List<Dep>> preds = new HashMap<>();
        Map<String, List<Dep>> succs = new HashMap<>();
        for (Dep d : deps) {
            if (!byId.containsKey(d.pred()) || !byId.containsKey(d.succ())) {
                continue;
            }
            preds.computeIfAbsent(d.succ(), k -> new ArrayList<>()).add(d);
            succs.computeIfAbsent(d.pred(), k -> new ArrayList<>()).add(d);
        }
        List<String> order = topo(byId.keySet(), succs, preds);

        Map<String, Integer> es = new HashMap<>();
        Map<String, Integer> ef = new HashMap<>();
        for (String id : order) {
            Task t = byId.get(id);
            int s = t.start();
            for (Dep d : preds.getOrDefault(id, List.of())) {
                int pes = es.get(d.pred());
                int pef = ef.get(d.pred());
                int c = switch (d.type() == null ? "FS" : d.type()) {
                    case "SS" -> pes + d.lag();
                    case "FF" -> pef + d.lag() - t.dur();
                    case "SF" -> pes + d.lag() - t.dur();
                    default -> pef + d.lag();
                };
                s = Math.max(s, c);
            }
            es.put(id, s);
            ef.put(id, s + t.dur());
        }
        int end = ef.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        Map<String, Integer> lf = new HashMap<>();
        Map<String, Integer> ls = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            String id = order.get(i);
            Task t = byId.get(id);
            int f = end;
            for (Dep d : succs.getOrDefault(id, List.of())) {
                int sls = ls.get(d.succ());
                int slf = lf.get(d.succ());
                int c = switch (d.type() == null ? "FS" : d.type()) {
                    case "SS" -> sls - d.lag() + t.dur();
                    case "FF" -> slf - d.lag();
                    case "SF" -> slf - d.lag() + t.dur();
                    default -> sls - d.lag();
                };
                f = Math.min(f, c);
            }
            lf.put(id, f);
            ls.put(id, f - t.dur());
        }
        Map<String, Integer> tf = new HashMap<>();
        Set<String> critical = new HashSet<>();
        for (String id : order) {
            int fl = ls.get(id) - es.get(id);
            tf.put(id, fl);
            if (fl <= 0) {
                critical.add(id);
            }
        }
        return new Result(es, ef, tf, critical, end);
    }

    /** 위상 정렬. 순환이면 CycleException. */
    static List<String> topo(Collection<String> ids, Map<String, List<Dep>> succs, Map<String, List<Dep>> preds) {
        Map<String, Integer> indeg = new HashMap<>();
        for (String id : ids) {
            indeg.put(id, preds.getOrDefault(id, List.of()).size());
        }
        Deque<String> q = new ArrayDeque<>();
        for (String id : ids) {
            if (indeg.get(id) == 0) {
                q.add(id);
            }
        }
        List<String> out = new ArrayList<>();
        while (!q.isEmpty()) {
            String id = q.poll();
            out.add(id);
            for (Dep d : succs.getOrDefault(id, List.of())) {
                int v = indeg.merge(d.succ(), -1, Integer::sum);
                if (v == 0) {
                    q.add(d.succ());
                }
            }
        }
        if (out.size() != ids.size()) {
            throw new CycleException("선후행 관계에 순환이 있습니다.");
        }
        return out;
    }

    /** 의존 그래프만으로 순환 여부 확인 (일정 없는 작업 포함) */
    public static void checkCycle(Collection<String> ids, Collection<Dep> deps) {
        Map<String, List<Dep>> preds = new HashMap<>();
        Map<String, List<Dep>> succs = new HashMap<>();
        Set<String> idSet = new HashSet<>(ids);
        for (Dep d : deps) {
            if (!idSet.contains(d.pred()) || !idSet.contains(d.succ())) {
                continue;
            }
            preds.computeIfAbsent(d.succ(), k -> new ArrayList<>()).add(d);
            succs.computeIfAbsent(d.pred(), k -> new ArrayList<>()).add(d);
        }
        topo(ids, succs, preds);
    }
}
