package kr.go.py.calc.web;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import com.nexacro.uiadapter17.spring.core.annotation.ParamDataSet;
import com.nexacro.uiadapter17.spring.core.annotation.ParamVariable;
import com.nexacro.uiadapter17.spring.core.data.NexacroResult;

import kr.go.py.calc.service.PayCalcService;

/**
 * 급여계산 컨트롤러 (Nexacro UI Adapter)
 */
@Controller
public class PayCalcController {

    @Resource(name = "payCalcService")
    private PayCalcService payCalcService;

    @RequestMapping("/py/calc/selectList.do")
    public NexacroResult selectList(@ParamDataSet(name = "ds_search") Map<String, Object> search) {
        NexacroResult result = new NexacroResult();
        List<Map<String, Object>> list = payCalcService.selectPayCalcList(search);
        result.addDataSet("ds_list", list);
        return result;
    }

    @RequestMapping("/py/calc/calculate.do")
    public NexacroResult calculate(@ParamVariable(name = "payYm") String payYm,
                                   @ParamVariable(name = "userId") String userId) {
        payCalcService.calculate(payYm, userId);
        return new NexacroResult();
    }

    @RequestMapping("/py/calc/save.do")
    public NexacroResult save(@ParamDataSet(name = "ds_list") List<Map<String, Object>> list) {
        payCalcService.savePayCalc(list);
        return new NexacroResult();
    }

    @RequestMapping("/py/calc/close.do")
    public NexacroResult close(@ParamDataSet(name = "ds_search") Map<String, Object> search) {
        payCalcService.closePay(search);
        return new NexacroResult();
    }
}
