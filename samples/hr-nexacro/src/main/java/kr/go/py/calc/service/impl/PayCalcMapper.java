package kr.go.py.calc.service.impl;

import java.util.List;
import java.util.Map;

import org.egovframe.rte.psl.dataaccess.mapper.Mapper;

@Mapper("payCalcMapper")
public interface PayCalcMapper {
    List<Map<String, Object>> selectPayCalcList(Map<String, Object> search);
    int deletePayItem(Map<String, Object> param);
    void callPayCalc(Map<String, Object> param);
    int updatePayCalc(Map<String, Object> row);
    int updateCloseYn(Map<String, Object> search);
    int insertCloseHist(Map<String, Object> search);
}
