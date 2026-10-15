package kr.go.py.allow.web;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import com.nexacro.uiadapter17.spring.core.annotation.ParamDataSet;
import com.nexacro.uiadapter17.spring.core.annotation.ParamVariable;
import com.nexacro.uiadapter17.spring.core.data.NexacroResult;

import kr.go.py.allow.service.AllowService;

@Controller
public class AllowController {

    @Resource(name = "allowService")
    private AllowService allowService;

    @RequestMapping("/py/allow/selectList.do")
    public NexacroResult selectList(@ParamDataSet(name = "ds_search") Map<String, Object> search) {
        NexacroResult result = new NexacroResult();
        result.addDataSet("ds_allow", allowService.selectAllowList(search));
        return result;
    }

    @RequestMapping("/py/allow/save.do")
    public NexacroResult save(@ParamDataSet(name = "ds_allow") List<Map<String, Object>> list) {
        allowService.saveAllow(list);
        return new NexacroResult();
    }
}
