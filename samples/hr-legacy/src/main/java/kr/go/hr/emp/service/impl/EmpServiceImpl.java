package kr.go.hr.emp.service.impl;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import egovframework.rte.fdl.cmmn.EgovAbstractServiceImpl;
import kr.go.hr.emp.service.EmpService;
import kr.go.hr.emp.service.EmpVO;

/**
 * 사원관리 서비스 구현
 */
@Service("empService")
public class EmpServiceImpl extends EgovAbstractServiceImpl implements EmpService {

    @Resource(name = "empMapper")
    private EmpMapper empMapper;

    @Override
    public List<EmpVO> selectEmpList(EmpVO searchVO) throws Exception {
        return empMapper.selectEmpList(searchVO);
    }

    @Override
    public List<EmpVO> selectEmpPopList(EmpVO searchVO) throws Exception {
        return empMapper.selectEmpPopList(searchVO);
    }

    @Override
    public EmpVO selectEmp(String empNo) throws Exception {
        return empMapper.selectEmp(empNo);
    }

    @Override
    @Transactional
    public void saveEmp(List<EmpVO> rows, String userId) throws Exception {
        for (EmpVO row : rows) {
            row.setRegId(userId);
            if ("I".equals(row.getRowStatus())) {
                row.setEmpNo(empMapper.selectNextEmpNo());
                empMapper.insertEmp(row);
            } else if ("U".equals(row.getRowStatus())) {
                empMapper.updateEmp(row);
            }
            // 변경 이력 적재
            empMapper.insertEmpHist(row);
        }
    }

    @Override
    @Transactional
    public void deleteEmp(String empNo, String userId) throws Exception {
        EmpVO row = new EmpVO();
        row.setEmpNo(empNo);
        row.setRegId(userId);
        row.setRowStatus("D");
        empMapper.deleteEmp(row);
        empMapper.insertEmpHist(row);
    }
}
