package kr.go.hr.dept.service.impl;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import egovframework.rte.fdl.cmmn.EgovAbstractServiceImpl;
import kr.go.hr.dept.service.DeptService;
import kr.go.hr.dept.service.DeptVO;

@Service("deptService")
public class DeptServiceImpl extends EgovAbstractServiceImpl implements DeptService {

    @Resource(name = "deptMapper")
    private DeptMapper deptMapper;

    @Override
    public List<DeptVO> selectDeptList(DeptVO searchVO) throws Exception {
        return deptMapper.selectDeptList(searchVO);
    }

    @Override
    @Transactional
    public void saveDept(DeptVO vo) throws Exception {
        deptMapper.mergeDept(vo);
    }

    @Override
    @Transactional
    public void updateSortOrder(List<DeptVO> rows) throws Exception {
        deptMapper.updateSortOrd(rows);
    }
}
