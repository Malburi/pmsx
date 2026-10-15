package kr.go.hr.edu.service;

import java.util.List;
import java.util.Map;

public interface EduService {

    List<EduVO> selectEduList(EduVO searchVO) throws Exception;

    Map<String, Object> selectEduSummary(EduVO searchVO) throws Exception;

    void saveEdu(List<EduVO> rows, String userId) throws Exception;
}
