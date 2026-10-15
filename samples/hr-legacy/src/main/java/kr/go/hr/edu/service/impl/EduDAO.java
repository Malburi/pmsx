package kr.go.hr.edu.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import egovframework.rte.psl.dataaccess.EgovAbstractDAO;
import kr.go.hr.edu.service.EduVO;

/**
 * 교육이력 DAO (iBatis sqlMap - 전자정부 2.x 방식)
 */
@Repository("eduDAO")
public class EduDAO extends EgovAbstractDAO {

    @SuppressWarnings("unchecked")
    public List<EduVO> selectEduList(EduVO searchVO) throws Exception {
        return (List<EduVO>) list("edu.selectEduList", searchVO);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> selectEduSummary(EduVO searchVO) throws Exception {
        return (Map<String, Object>) getSqlMapClientTemplate().queryForObject("edu.selectEduSummary", searchVO);
    }

    public void insertEdu(EduVO vo) throws Exception {
        insert("edu.insertEdu", vo);
    }

    public int updateEdu(EduVO vo) throws Exception {
        return update("edu.updateEdu", vo);
    }
}
