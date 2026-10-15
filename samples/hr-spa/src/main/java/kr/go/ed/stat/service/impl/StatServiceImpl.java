package kr.go.ed.stat.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import kr.go.ed.stat.service.StatService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StatServiceImpl implements StatService {

    private final StatMapper statMapper;

    @Override
    public List<Map<String, Object>> selectEduStat(String year) {
        return statMapper.selectEduStat(year);
    }
}
