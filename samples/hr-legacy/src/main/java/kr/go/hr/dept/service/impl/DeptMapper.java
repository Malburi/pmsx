package kr.go.hr.dept.service.impl;

import java.util.List;

import egovframework.rte.psl.dataaccess.mapper.Mapper;
import kr.go.hr.dept.service.DeptVO;

@Mapper("deptMapper")
public interface DeptMapper {

    List<DeptVO> selectDeptList(DeptVO searchVO);

    int mergeDept(DeptVO vo);

    int updateSortOrd(List<DeptVO> rows);
}
