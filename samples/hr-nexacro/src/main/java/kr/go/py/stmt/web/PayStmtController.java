package kr.go.py.stmt.web;

import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import com.nexacro.uiadapter17.spring.core.annotation.ParamDataSet;
import com.nexacro.uiadapter17.spring.core.annotation.ParamVariable;
import com.nexacro.uiadapter17.spring.core.data.NexacroResult;

import kr.go.py.stmt.service.PayStmtService;

/** 급여명세 컨트롤러 — 생성자 주입 */
@Controller
@RequestMapping("/py/stmt")
public class PayStmtController {

    private final PayStmtService payStmtService;

    public PayStmtController(PayStmtService payStmtService) {
        this.payStmtService = payStmtService;
    }

    @RequestMapping("/selectStmt.do")
    public NexacroResult selectStmt(@ParamDataSet(name = "ds_search") Map<String, Object> search) {
        NexacroResult result = new NexacroResult();
        result.addDataSet("ds_allow", payStmtService.selectAllowList(search));
        result.addDataSet("ds_deduct", payStmtService.selectDeductList(search));
        return result;
    }

    @RequestMapping("/sendMail.do")
    public NexacroResult sendMail(@ParamDataSet(name = "ds_search") Map<String, Object> search) {
        payStmtService.sendStmtMail(search);
        return new NexacroResult();
    }
}
