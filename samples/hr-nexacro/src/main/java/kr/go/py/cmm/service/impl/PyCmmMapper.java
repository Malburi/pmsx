package kr.go.py.cmm.service.impl;

import java.util.List;
import java.util.Map;

import org.egovframe.rte.psl.dataaccess.mapper.Mapper;

@Mapper("pyCmmMapper")
public interface PyCmmMapper {
    List<Map<String, Object>> selectDeptList();
    List<Map<String, Object>> selectEmpPopList(String empNm);
    List<Map<String, Object>> selectCodeList(String grpCd);
}
