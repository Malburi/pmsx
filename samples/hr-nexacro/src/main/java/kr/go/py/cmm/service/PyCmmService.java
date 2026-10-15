package kr.go.py.cmm.service;

import java.util.List;
import java.util.Map;

public interface PyCmmService {
    List<Map<String, Object>> selectDeptList();
    List<Map<String, Object>> selectEmpPopList(String empNm);
    List<Map<String, Object>> selectCodeList(String grpCd);
}
