package kr.go.cmm.code.service.impl;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import egovframework.rte.fdl.cmmn.EgovAbstractServiceImpl;
import kr.go.cmm.code.service.CmmCodeService;
import kr.go.cmm.code.service.CodeVO;

@Service("cmmCodeService")
public class CmmCodeServiceImpl extends EgovAbstractServiceImpl implements CmmCodeService {

    @Resource(name = "cmmCodeMapper")
    private CmmCodeMapper cmmCodeMapper;

    @Override
    public List<CodeVO> selectDeptCodeList(String useYn) throws Exception {
        return cmmCodeMapper.selectDeptCodeList(useYn);
    }

    @Override
    public List<CodeVO> selectCodeList(String grpCd) throws Exception {
        return cmmCodeMapper.selectCodeList(grpCd);
    }
}
