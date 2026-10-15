package kr.go.hr.emp.service;

import java.util.List;

/**
 * 사원관리 서비스
 */
public interface EmpService {

    List<EmpVO> selectEmpList(EmpVO searchVO) throws Exception;

    List<EmpVO> selectEmpPopList(EmpVO searchVO) throws Exception;

    EmpVO selectEmp(String empNo) throws Exception;

    void saveEmp(List<EmpVO> rows, String userId) throws Exception;

    void deleteEmp(String empNo, String userId) throws Exception;
}
