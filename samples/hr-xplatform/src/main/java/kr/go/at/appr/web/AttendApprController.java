package kr.go.at.appr.web;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;

import com.tobesoft.xplatform.data.PlatformData;

import kr.go.at.req.service.impl.AttendMapper;

/** 근태승인 (MiPlatform 화면 AT0102) — 컨트롤러가 Mapper 를 바로 호출하는 레거시 패턴. */
@Controller
public class AttendApprController {

    @Resource(name = "attendMapper")
    private AttendMapper attendMapper;

    @RequestMapping("/at/appr/selectList.do")
    public PlatformData selectList(PlatformData in) {
        PlatformData out = new PlatformData();
        out.addDataSet(attendMapper.selectApprList(in.getDataSet("ds_cond")));
        return out;
    }

    @RequestMapping("/at/appr/approve.do")
    @Transactional
    public PlatformData approve(PlatformData in) {
        attendMapper.approve(in.getDataSet("ds_appr"));
        return new PlatformData();
    }

    @RequestMapping("/at/appr/reject.do")
    @Transactional
    public PlatformData reject(PlatformData in) {
        attendMapper.reject(in.getDataSet("ds_appr"));
        return new PlatformData();
    }
}
