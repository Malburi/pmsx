package kr.go.bw.req.web;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import kr.go.bw.req.service.WelfareReqService;

/** 복지포인트 신청 — WebSquare JSON 서브미션 대상. */
@Controller
@RequestMapping("/bw/req")
public class WelfareReqController {

    @Resource(name = "welfareReqService")
    private WelfareReqService welfareReqService;

    @RequestMapping("/selectList.do")
    @ResponseBody
    public Map<String, Object> selectList(@RequestBody Map<String, Object> param) {
        return Map.of("dlt_req", welfareReqService.selectReqList(param));
    }

    @RequestMapping("/save.do")
    @ResponseBody
    public Map<String, Object> save(@RequestBody Map<String, Object> param) {
        welfareReqService.saveReq((List<Map<String, Object>>) param.get("dlt_req"));
        return Map.of("result", "OK");
    }

    @RequestMapping("/delete.do")
    @ResponseBody
    public Map<String, Object> delete(@RequestBody Map<String, Object> param) {
        welfareReqService.deleteReq((List<Map<String, Object>>) param.get("dlt_req"));
        return Map.of("result", "OK");
    }
}
