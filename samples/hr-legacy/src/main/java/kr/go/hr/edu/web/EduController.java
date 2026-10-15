package kr.go.hr.edu.web;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import kr.go.cmm.session.LoginUser;
import kr.go.hr.edu.service.EduService;
import kr.go.hr.edu.service.EduVO;

/**
 * 교육이력관리 컨트롤러 (2024.03 신규 - 생성자 주입)
 */
@Controller
@RequestMapping("/hr/edu")
public class EduController {

    private final EduService eduService;

    public EduController(EduService eduService) {
        this.eduService = eduService;
    }

    /** 교육이력관리 화면 */
    @GetMapping("/HR0105.do")
    public String eduMain() {
        return "hr/edu/HR0105";
    }

    /** 교육 이력 목록 */
    @PostMapping("/list.do")
    public ModelAndView selectEduList(@RequestBody EduVO searchVO) throws Exception {
        List<EduVO> list = eduService.selectEduList(searchVO);
        return new ModelAndView("jsonView", "list", list);
    }

    /** 교육 통계 */
    @PostMapping("/summary.do")
    public ModelAndView selectEduSummary(@RequestBody EduVO searchVO) throws Exception {
        Map<String, Object> summary = eduService.selectEduSummary(searchVO);
        return new ModelAndView("jsonView", summary);
    }

    /** 교육 이력 저장 */
    @PostMapping("/save.do")
    public ModelAndView saveEdu(@RequestBody EduVO saveVO) throws Exception {
        eduService.saveEdu(saveVO.getRows(), LoginUser.getUserId());
        return new ModelAndView("jsonView", "result", "OK");
    }

    /** 엑셀 다운로드 */
    @GetMapping("/excel.do")
    public ModelAndView excelEdu(EduVO searchVO) throws Exception {
        List<EduVO> list = eduService.selectEduList(searchVO);
        return new ModelAndView("excelView", "list", list);
    }
}
