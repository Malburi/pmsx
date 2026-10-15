package kr.go.hr.dept.service;

import java.util.List;

public interface DeptService {

    List<DeptVO> selectDeptList(DeptVO searchVO) throws Exception;

    void saveDept(DeptVO vo) throws Exception;

    void updateSortOrder(List<DeptVO> rows) throws Exception;
}
