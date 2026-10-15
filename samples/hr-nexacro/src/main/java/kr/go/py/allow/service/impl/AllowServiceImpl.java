package kr.go.py.allow.service.impl;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.go.py.allow.service.AllowService;

@Service("allowService")
public class AllowServiceImpl implements AllowService {

    @Resource(name = "allowMapper")
    private AllowMapper allowMapper;

    @Override
    public List<Map<String, Object>> selectAllowList(Map<String, Object> search) {
        return allowMapper.selectAllowList(search);
    }

    @Override
    @Transactional
    public void saveAllow(List<Map<String, Object>> list) {
        for (Map<String, Object> row : list) {
            String rowType = String.valueOf(row.get("_RowType_"));
            if ("I".equals(rowType)) {
                allowMapper.insertAllow(row);
            } else if ("U".equals(rowType)) {
                allowMapper.updateAllow(row);
            } else if ("D".equals(rowType)) {
                allowMapper.deleteAllow(row);
            }
            allowMapper.insertAllowHist(row);
        }
    }
}
