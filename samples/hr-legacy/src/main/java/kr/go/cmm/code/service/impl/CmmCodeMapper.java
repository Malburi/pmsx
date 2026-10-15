package kr.go.cmm.code.service.impl;

import java.util.List;

import egovframework.rte.psl.dataaccess.mapper.Mapper;
import kr.go.cmm.code.service.CodeVO;

@Mapper("cmmCodeMapper")
public interface CmmCodeMapper {

    List<CodeVO> selectDeptCodeList(String useYn);

    List<CodeVO> selectCodeList(String grpCd);
}
