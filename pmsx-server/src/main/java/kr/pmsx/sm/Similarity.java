package kr.pmsx.sm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 유사 문서 추천 (docs/13 §5): 한글은 글자 2-gram, 영문·숫자는 단어 단위로 자른 TF-IDF 코사인 유사도.
 * 외부 라이브러리 없이 요청마다 계산한다(프로젝트 KB·종료 SR 수천 건 규모).
 */
public final class Similarity {

    private Similarity() {
    }

    public record Doc(String kind, String id, String no, String title, String text) {
    }

    public record Scored(Doc doc, double score) {
    }

    /** 토큰: 한글이 섞인 단어 → 2-gram(1글자 단어는 그대로), 그 외 2자 이상 단어 */
    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String w : text.toLowerCase().split("[^\\p{L}\\p{Nd}]+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (w.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL)) {
                if (w.length() == 1) {
                    out.add(w);
                }
                for (int i = 0; i + 1 < w.length(); i++) {
                    out.add(w.substring(i, i + 2));
                }
            } else if (w.length() >= 2) {
                out.add(w);
            }
        }
        return out;
    }

    private static Map<String, Integer> tf(String text) {
        Map<String, Integer> m = new HashMap<>();
        for (String t : tokens(text)) {
            m.merge(t, 1, Integer::sum);
        }
        return m;
    }

    /** query 와 비슷한 문서 상위 limit 개 (score ≥ min, 내림차순) */
    public static List<Scored> top(String query, List<Doc> docs, int limit, double min) {
        Map<String, Integer> q = tf(query);
        if (q.isEmpty() || docs.isEmpty()) {
            return List.of();
        }
        List<Map<String, Integer>> vecs = new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();
        for (Doc d : docs) {
            Map<String, Integer> v = tf(d.title() + " " + (d.text() == null ? "" : d.text()));
            vecs.add(v);
            v.keySet().forEach(t -> df.merge(t, 1, Integer::sum));
        }
        int n = docs.size();
        Map<String, Double> idf = new HashMap<>();
        df.forEach((t, c) -> idf.put(t, Math.log((n + 1.0) / (c + 1.0)) + 1.0));
        Map<String, Double> qv = weigh(q, idf, n);
        double qn = norm(qv);
        List<Scored> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Double> dv = weigh(vecs.get(i), idf, n);
            double dn = norm(dv);
            if (dn == 0 || qn == 0) {
                continue;
            }
            double dot = 0;
            for (Map.Entry<String, Double> e : qv.entrySet()) {
                Double x = dv.get(e.getKey());
                if (x != null) {
                    dot += x * e.getValue();
                }
            }
            double s = dot / (qn * dn);
            if (s >= min) {
                out.add(new Scored(docs.get(i), Math.round(s * 1000) / 1000.0));
            }
        }
        out.sort(Comparator.comparingDouble(Scored::score).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static Map<String, Double> weigh(Map<String, Integer> tf, Map<String, Double> idf, int n) {
        Map<String, Double> out = new HashMap<>();
        double unseen = Math.log(n + 1.0) + 1.0;
        tf.forEach((t, c) -> out.put(t, (1 + Math.log(c)) * idf.getOrDefault(t, unseen)));
        return out;
    }

    private static double norm(Map<String, Double> v) {
        double s = 0;
        for (double x : v.values()) {
            s += x * x;
        }
        return Math.sqrt(s);
    }

    /** 두 문장 2-gram 자카드 유사도 (문제 후보 제목 묶음용) */
    public static double jaccard(String a, String b) {
        var x = new java.util.HashSet<>(tokens(a));
        var y = new java.util.HashSet<>(tokens(b));
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String t : x) {
            if (y.contains(t)) {
                inter++;
            }
        }
        return inter / (double) (x.size() + y.size() - inter);
    }
}
