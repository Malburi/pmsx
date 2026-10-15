package kr.go.py.allow.service;

import java.util.List;
import java.util.Map;

public interface AllowService {
    List<Map<String, Object>> selectAllowList(Map<String, Object> search);
    void saveAllow(List<Map<String, Object>> list);
}
