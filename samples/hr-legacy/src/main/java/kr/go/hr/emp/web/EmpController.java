package kr.go.hr.emp.web;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import kr.go.cmm.session.LoginUser;
import kr.go.hr.emp.service.EmpSaveVO;
import kr.go.hr.emp.service.EmpService;
import kr.go.hr.emp.service.EmpVO;

/**
 * 사원관리 컨트롤러
 * @author 개발팀
 * @since 2019.03.02
 */
@Controller
public class EmpController {

    @Resource(name = "empService")
    private EmpService empService;

    /** 사원관리 화면 */
    @RequestMapping("/hr/emp/HR0101.do")
    public String empMain(ModelMap model) {
        return "hr/emp/HR0101";
    }

    /** 사원검색 팝업 */
    @RequestMapping("/hr/emp/HR0101P01.do")
    public String empSearchPopup(ModelMap model) {
        return "hr/emp/HR0101P01";
    }

    /** 사원상세 팝업 */
    @RequestMapping("/hr/emp/HR0102.do")
    public String empDetailPopup(@RequestParam("empNo") String empNo, ModelMap model) {
        model.addAttribute("empNo", empNo);
        return "hr/emp/HR0102";
    }

    /** 사원 목록 조회 */
    @RequestMapping(value = "/hr/emp/list.do", method = RequestMethod.POST)
    public ModelAndView selectEmpList(@RequestBody EmpVO searchVO) throws Exception {
        List<EmpVO> list = empService.selectEmpList(searchVO);
        return new ModelAndView("jsonView", "list", list);
    }

    /** 사원검색 팝업 목록 */
    @RequestMapping(value = "/hr/emp/popList.do", method = RequestMethod.POST)
    public ModelAndView selectEmpPopList(@RequestBody EmpVO searchVO) throws Exception {
        List<EmpVO> list = empService.selectEmpPopList(searchVO);
        return new ModelAndView("jsonView", "list", list);
    }

    /** 사원 상세 조회 */
    @RequestMapping(value = "/hr/emp/detail.do", method = RequestMethod.POST)
    public ModelAndView selectEmp(@RequestParam("empNo") String empNo) throws Exception {
        EmpVO emp = empService.selectEmp(empNo);
        return new ModelAndView("jsonView", "emp", emp);
    }

    /** 사원 저장 (신규/수정) */
    @RequestMapping(value = "/hr/emp/save.do", method = RequestMethod.POST)
    public ModelAndView saveEmp(@RequestBody EmpSaveVO saveVO, LoginUser user) throws Exception {
        empService.saveEmp(saveVO.getRows(), user.getUserId());
        return new ModelAndView("jsonView", "result", "OK");
    }

    /** 사원 삭제 (논리 삭제) */
    @RequestMapping(value = "/hr/emp/delete.do", method = RequestMethod.POST)
    public ModelAndView deleteEmp(@RequestBody EmpVO empVO, LoginUser user) throws Exception {
        empService.deleteEmp(empVO.getEmpNo(), user.getUserId());
        return new ModelAndView("jsonView", "result", "OK");
    }

    /** 사원 목록 엑셀 다운로드 */
    @RequestMapping("/hr/emp/excel.do")
    public String excelEmp(EmpVO searchVO, ModelMap model) throws Exception {
        List<EmpVO> list = empService.selectEmpList(searchVO);
        model.addAttribute("list", list);
        model.addAttribute("fileName", "사원목록");
        return "excelView";
    }
}
