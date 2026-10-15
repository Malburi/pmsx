package kr.go.hr.emp.service.impl;

import java.util.List;

import egovframework.rte.psl.dataaccess.mapper.Mapper;
import kr.go.hr.emp.service.EmpVO;

/**
 * 사원 매퍼 (EmpMapper.xml)
 */
@Mapper("empMapper")
public interface EmpMapper {

    List<EmpVO> selectEmpList(EmpVO searchVO);

    List<EmpVO> selectEmpPopList(EmpVO searchVO);

    EmpVO selectEmp(String empNo);

    String selectNextEmpNo();

    int insertEmp(EmpVO vo);

    int updateEmp(EmpVO vo);

    int deleteEmp(EmpVO vo);

    int insertEmpHist(EmpVO vo);
}
