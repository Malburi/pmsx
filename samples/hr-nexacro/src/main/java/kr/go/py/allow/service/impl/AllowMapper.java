package kr.go.py.allow.service.impl;

import java.util.List;
import java.util.Map;

import org.egovframe.rte.psl.dataaccess.mapper.Mapper;

@Mapper("allowMapper")
public interface AllowMapper {
    List<Map<String, Object>> selectAllowList(Map<String, Object> search);
    int insertAllow(Map<String, Object> row);
    int updateAllow(Map<String, Object> row);
    int deleteAllow(Map<String, Object> row);
    int insertAllowHist(Map<String, Object> row);
}
