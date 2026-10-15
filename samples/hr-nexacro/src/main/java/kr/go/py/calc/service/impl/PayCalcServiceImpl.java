package kr.go.py.calc.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.go.py.calc.service.PayCalcService;
import lombok.RequiredArgsConstructor;

@Service("payCalcService")
@RequiredArgsConstructor
public class PayCalcServiceImpl implements PayCalcService {

    private final PayCalcMapper payCalcMapper;

    @Override
    public List<Map<String, Object>> selectPayCalcList(Map<String, Object> search) {
        return payCalcMapper.selectPayCalcList(search);
    }

    @Override
    @Transactional
    public void calculate(String payYm, String userId) {
        Map<String, Object> param = new HashMap<>();
        param.put("payYm", payYm);
        param.put("userId", userId);
        payCalcMapper.deletePayItem(param);
        payCalcMapper.callPayCalc(param);
    }

    @Override
    @Transactional
    public void savePayCalc(List<Map<String, Object>> list) {
        for (Map<String, Object> row : list) {
            if ("U".equals(row.get("_RowType_"))) {
                payCalcMapper.updatePayCalc(row);
            }
        }
    }

    @Override
    @Transactional
    public void closePay(Map<String, Object> search) {
        payCalcMapper.updateCloseYn(search);
        payCalcMapper.insertCloseHist(search);
    }
}
