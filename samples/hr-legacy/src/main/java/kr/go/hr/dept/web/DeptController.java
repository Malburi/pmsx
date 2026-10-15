package kr.go.hr.dept.web;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import kr.go.hr.dept.service.DeptService;
import kr.go.hr.dept.service.DeptVO;

/**
 * 부서관리 컨트롤러
 */
@Controller
public class DeptController {

    @Resource(name = "deptService")
    private DeptService deptService;

    @RequestMapping("/hr/dept/HR0104.do")
    public String deptMain() {
        return "hr/dept/HR0104";
    }

    @RequestMapping(value = "/hr/dept/list.do", method = RequestMethod.POST)
    public ModelAndView selectDeptList(@RequestBody DeptVO searchVO) throws Exception {
        List<DeptVO> list = deptService.selectDeptList(searchVO);
        return new ModelAndView("jsonView", "list", list);
    }

    @RequestMapping(value = "/hr/dept/save.do", method = RequestMethod.POST)
    public ModelAndView saveDept(@RequestBody DeptVO vo) throws Exception {
        deptService.saveDept(vo);
        return new ModelAndView("jsonView", "result", "OK");
    }

    /** 부서 정렬순서 일괄 적용 */
    @RequestMapping(value = "/hr/dept/sort.do", method = RequestMethod.POST)
    public ModelAndView updateSortOrder(@RequestBody List<DeptVO> rows) throws Exception {
        deptService.updateSortOrder(rows);
        return new ModelAndView("jsonView", "result", "OK");
    }
}
