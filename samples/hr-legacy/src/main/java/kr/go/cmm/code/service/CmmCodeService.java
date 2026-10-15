package kr.go.cmm.code.service;

import java.util.List;

public interface CmmCodeService {

    List<CodeVO> selectDeptCodeList(String useYn) throws Exception;

    List<CodeVO> selectCodeList(String grpCd) throws Exception;
}
