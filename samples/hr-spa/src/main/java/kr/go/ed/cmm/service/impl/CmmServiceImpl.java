package kr.go.ed.cmm.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import kr.go.ed.cmm.service.CmmService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CmmServiceImpl implements CmmService {

    private final CmmMapper cmmMapper;

    @Override
    public List<Map<String, Object>> selectDeptList() {
        return cmmMapper.selectDeptList();
    }
}
