package kr.go.hr.edu.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.go.hr.edu.service.EduService;
import kr.go.hr.edu.service.EduVO;
import kr.go.hr.emp.service.EmpVO;
import kr.go.hr.emp.service.impl.EmpMapper;
import lombok.RequiredArgsConstructor;

/**
 * 교육이력 서비스 구현 (Lombok 생성자 주입, iBatis DAO + MyBatis Mapper 혼용)
 */
@Service("eduService")
@RequiredArgsConstructor
public class EduServiceImpl implements EduService {

    private final EduDAO eduDAO;
    private final EmpMapper empMapper;

    @Override
    public List<EduVO> selectEduList(EduVO searchVO) throws Exception {
        return eduDAO.selectEduList(searchVO);
    }

    @Override
    public Map<String, Object> selectEduSummary(EduVO searchVO) throws Exception {
        return eduDAO.selectEduSummary(searchVO);
    }

    @Override
    @Transactional
    public void saveEdu(List<EduVO> rows, String userId) throws Exception {
        for (EduVO row : rows) {
            EmpVO emp = empMapper.selectEmp(row.getEmpNo());
            if (emp == null) {
                throw new IllegalArgumentException("사원 없음: " + row.getEmpNo());
            }
            row.setRegId(userId);
            if ("I".equals(row.getRowStatus())) {
                eduDAO.insertEdu(row);
            } else {
                eduDAO.updateEdu(row);
            }
        }
    }
}
