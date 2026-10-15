package kr.go.bw.req.service.impl;

import java.util.List;
import java.util.Map;

import egovframework.rte.psl.dataaccess.mapper.Mapper;

@Mapper("welfareReqMapper")
public interface WelfareReqMapper {
    List<Map<String, Object>> selectReqList(Map<String, Object> param);

    int insertReq(Map<String, Object> row);

    int updateReq(Map<String, Object> row);

    int deleteReq(Map<String, Object> row);

    int insertReqHist(Map<String, Object> row);
}
