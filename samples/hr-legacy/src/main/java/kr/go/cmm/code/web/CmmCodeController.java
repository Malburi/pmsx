package kr.go.cmm.code.web;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import kr.go.cmm.code.service.CmmCodeService;
import kr.go.cmm.code.service.CodeVO;

/**
 * 공통코드 조회 (콤보용)
 */
@Controller
public class CmmCodeController {

    @Resource(name = "cmmCodeService")
    private CmmCodeService cmmCodeService;

    @GetMapping("/cmm/code/dept.do")
    public ModelAndView selectDeptCodeList(@RequestParam(value = "useYn", required = false) String useYn) throws Exception {
        List<CodeVO> list = cmmCodeService.selectDeptCodeList(useYn);
        return new ModelAndView("jsonView", "list", list);
    }

    @GetMapping("/cmm/code/list.do")
    public ModelAndView selectCodeList(@RequestParam("grpCd") String grpCd) throws Exception {
        List<CodeVO> list = cmmCodeService.selectCodeList(grpCd);
        return new ModelAndView("jsonView", "list", list);
    }
}
