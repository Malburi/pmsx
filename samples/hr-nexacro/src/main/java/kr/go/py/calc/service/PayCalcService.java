package kr.go.py.calc.service;

import java.util.List;
import java.util.Map;

public interface PayCalcService {
    List<Map<String, Object>> selectPayCalcList(Map<String, Object> search);
    void calculate(String payYm, String userId);
    void savePayCalc(List<Map<String, Object>> list);
    void closePay(Map<String, Object> search);
}
