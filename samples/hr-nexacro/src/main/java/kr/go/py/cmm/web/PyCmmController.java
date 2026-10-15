package kr.go.py.cmm.web;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import com.nexacro.uiadapter17.spring.core.annotation.ParamDataSet;
import com.nexacro.uiadapter17.spring.core.annotation.ParamVariable;
import com.nexacro.uiadapter17.spring.core.data.NexacroResult;

import kr.go.py.cmm.service.PyCmmService;

@Controller
@RequestMapping("/py/cmm")
public class PyCmmController {

    @Resource(name = "pyCmmService")
    private PyCmmService pyCmmService;

    @RequestMapping("/selectDeptList.do")
    public NexacroResult selectDeptList() {
        NexacroResult result = new NexacroResult();
        result.addDataSet("ds_dept", pyCmmService.selectDeptList());
        return result;
    }

    @RequestMapping("/selectEmpPopList.do")
    public NexacroResult selectEmpPopList(@ParamVariable(name = "empNm") String empNm) {
        NexacroResult result = new NexacroResult();
        result.addDataSet("ds_emp", pyCmmService.selectEmpPopList(empNm));
        return result;
    }

    @RequestMapping("/selectCode.do")
    public NexacroResult selectCode(@ParamVariable(name = "grpCd") String grpCd) {
        NexacroResult result = new NexacroResult();
        result.addDataSet("ds_code", pyCmmService.selectCodeList(grpCd));
        return result;
    }
}
