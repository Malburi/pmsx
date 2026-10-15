package kr.go.py.stmt.service.impl;

import java.util.List;
import java.util.Map;

import org.egovframe.rte.psl.dataaccess.mapper.Mapper;

@Mapper("payStmtMapper")
public interface PayStmtMapper {
    List<Map<String, Object>> selectAllowList(Map<String, Object> search);
    List<Map<String, Object>> selectDeductList(Map<String, Object> search);
    int insertMailQueue(Map<String, Object> search);
}
