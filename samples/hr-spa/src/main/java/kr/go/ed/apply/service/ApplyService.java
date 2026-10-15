package kr.go.ed.apply.service;

import java.util.List;
import java.util.Map;

public interface ApplyService {
    List<Map<String, Object>> selectApplyList(Map<String, Object> cond);
    List<Map<String, Object>> selectMyApplyList();
    void insertApply(Map<String, Object> data);
    void cancelApply(String applyId);
}
