package kr.go.bw.std.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WelfareStdService {

    private final WelfareStdMapper welfareStdMapper;

    public List<Map<String, Object>> selectStd(Map<String, Object> param) {
        return welfareStdMapper.selectStd(param);
    }

    public List<Map<String, Object>> selectLimit(Map<String, Object> param) {
        return welfareStdMapper.selectLimit(param);
    }

    @Transactional
    public void saveStd(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            welfareStdMapper.mergeStd(row);
        }
    }

    @Transactional
    public void copyYear(Map<String, Object> param) {
        welfareStdMapper.callCopyYear(param);
    }
}
