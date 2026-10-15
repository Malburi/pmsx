package kr.go.bw.req.service.impl;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.go.bw.req.service.WelfareReqService;

@Service("welfareReqService")
public class WelfareReqServiceImpl implements WelfareReqService {

    @Resource(name = "welfareReqMapper")
    private WelfareReqMapper welfareReqMapper;

    @Override
    public List<Map<String, Object>> selectReqList(Map<String, Object> param) {
        return welfareReqMapper.selectReqList(param);
    }

    @Override
    @Transactional
    public void saveReq(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            String status = (String) row.get("rowStatus");
            if ("C".equals(status)) {
                welfareReqMapper.insertReq(row);
            } else if ("U".equals(status)) {
                welfareReqMapper.updateReq(row);
            }
            welfareReqMapper.insertReqHist(row);
        }
    }

    @Override
    @Transactional
    public void deleteReq(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            welfareReqMapper.deleteReq(row);
        }
    }
}
