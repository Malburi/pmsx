package kr.go.bw.req.service;

import java.util.List;
import java.util.Map;

public interface WelfareReqService {
    List<Map<String, Object>> selectReqList(Map<String, Object> param);

    void saveReq(List<Map<String, Object>> rows);

    void deleteReq(List<Map<String, Object>> rows);
}
