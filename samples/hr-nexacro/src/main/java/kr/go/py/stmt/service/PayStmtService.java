package kr.go.py.stmt.service;

import java.util.List;
import java.util.Map;

public interface PayStmtService {
    List<Map<String, Object>> selectAllowList(Map<String, Object> search);
    List<Map<String, Object>> selectDeductList(Map<String, Object> search);
    void sendStmtMail(Map<String, Object> search);
}
