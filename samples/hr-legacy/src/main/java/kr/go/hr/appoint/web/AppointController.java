package kr.go.hr.appoint.web;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import kr.go.cmm.session.LoginUser;
import kr.go.hr.appoint.service.AppointService;
import kr.go.hr.appoint.service.AppointVO;

/**
 * 발령관리 컨트롤러
 */
@Controller
@RequestMapping("/hr/appoint")
public class AppointController {

    @Resource(name = "appointService")
    private AppointService appointService;

    @RequestMapping("/HR0103.do")
    public String appointMain() {
        return "hr/appoint/HR0103";
    }

    @PostMapping("/list.do")
    public ModelAndView selectAppointList(@RequestBody AppointVO searchVO) throws Exception {
        List<AppointVO> list = appointService.selectAppointList(searchVO);
        return new ModelAndView("jsonView", "list", list);
    }

    @PostMapping("/save.do")
    public ModelAndView insertAppoint(@RequestBody AppointVO vo, LoginUser user) throws Exception {
        appointService.insertAppoint(vo, user.getUserId());
        return new ModelAndView("jsonView", "result", "OK");
    }

    /** 발령 확정 - 프로시저 호출로 사원 부서 변경 */
    @PostMapping("/confirm.do")
    public ModelAndView confirmAppoint(@RequestBody Map<String, Object> param, LoginUser user) throws Exception {
        String resultCd = appointService.confirmAppoint((String) param.get("apptNo"), user.getUserId());
        return new ModelAndView("jsonView", "result", resultCd);
    }
}
