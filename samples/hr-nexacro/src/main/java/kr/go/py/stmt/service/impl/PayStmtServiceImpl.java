package kr.go.py.stmt.service.impl;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import kr.go.py.stmt.service.PayStmtService;

@Service("payStmtService")
public class PayStmtServiceImpl implements PayStmtService {

    @Resource(name = "payStmtMapper")
    private PayStmtMapper payStmtMapper;

    @Override
    public List<Map<String, Object>> selectAllowList(Map<String, Object> search) {
        return payStmtMapper.selectAllowList(search);
    }

    @Override
    public List<Map<String, Object>> selectDeductList(Map<String, Object> search) {
        return payStmtMapper.selectDeductList(search);
    }

    @Override
    public void sendStmtMail(Map<String, Object> search) {
        payStmtMapper.insertMailQueue(search);
    }
}
