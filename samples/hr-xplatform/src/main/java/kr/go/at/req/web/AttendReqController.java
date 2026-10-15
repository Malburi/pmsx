package kr.go.at.req.web;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import com.tobesoft.xplatform.data.DataSet;
import com.tobesoft.xplatform.data.PlatformData;

import kr.go.at.req.service.AttendReqService;

/** 근태신청 — XPlatform/MiPlatform 트랜잭션 대상 (PlatformData 입출력). */
@Controller
public class AttendReqController {

    @Resource(name = "attendReqService")
    private AttendReqService attendReqService;

    @RequestMapping("/at/req/selectList.do")
    public PlatformData selectList(PlatformData in) {
        return attendReqService.selectReqList(in.getDataSet("ds_search"));
    }

    @RequestMapping("/at/req/save.do")
    public PlatformData save(PlatformData in) {
        attendReqService.saveReq(in.getDataSet("ds_req"));
        return new PlatformData();
    }

    @RequestMapping("/at/req/request.do")
    public PlatformData request(PlatformData in) {
        attendReqService.requestApproval(in.getDataSet("ds_req"));
        return new PlatformData();
    }
}
