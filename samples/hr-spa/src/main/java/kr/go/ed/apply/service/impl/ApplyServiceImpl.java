package kr.go.ed.apply.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.go.ed.apply.service.ApplyService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ApplyServiceImpl implements ApplyService {

    private final ApplyMapper applyMapper;

    @Override
    public List<Map<String, Object>> selectApplyList(Map<String, Object> cond) {
        return applyMapper.selectApplyList(cond);
    }

    @Override
    public List<Map<String, Object>> selectMyApplyList() {
        return applyMapper.selectMyApplyList();
    }

    @Override
    @Transactional
    public void insertApply(Map<String, Object> data) {
        applyMapper.insertApply(data);
    }

    @Override
    @Transactional
    public void cancelApply(String applyId) {
        applyMapper.updateApplyStatus(applyId);
    }
}
