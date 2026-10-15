package kr.go.ed.stat.service;

import java.util.List;
import java.util.Map;

public interface StatService {
    List<Map<String, Object>> selectEduStat(String year);
}
