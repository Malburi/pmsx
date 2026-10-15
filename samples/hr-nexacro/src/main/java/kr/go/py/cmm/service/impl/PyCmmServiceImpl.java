package kr.go.py.cmm.service.impl;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import kr.go.py.cmm.service.PyCmmService;

@Service("pyCmmService")
public class PyCmmServiceImpl implements PyCmmService {

    @Resource(name = "pyCmmMapper")
    private PyCmmMapper pyCmmMapper;

    @Override
    public List<Map<String, Object>> selectDeptList() {
        return pyCmmMapper.selectDeptList();
    }

    @Override
    public List<Map<String, Object>> selectEmpPopList(String empNm) {
        return pyCmmMapper.selectEmpPopList(empNm);
    }

    @Override
    public List<Map<String, Object>> selectCodeList(String grpCd) {
        return pyCmmMapper.selectCodeList(grpCd);
    }
}
