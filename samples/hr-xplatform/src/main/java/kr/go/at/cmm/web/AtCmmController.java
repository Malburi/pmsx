package kr.go.at.cmm.web;

import javax.annotation.Resource;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import com.tobesoft.xplatform.data.PlatformData;

import egovframework.rte.psl.dataaccess.EgovAbstractMapper;

@Controller
public class AtCmmController {

    @Resource(name = "sqlSession")
    private EgovAbstractMapper sqlSession;

    @RequestMapping("/at/cmm/code/list.do")
    public PlatformData codeList(PlatformData in) {
        PlatformData out = new PlatformData();
        out.addVariable("list", sqlSession.selectList("kr.go.at.cmm.AtCmmMapper.selectCodeList", in));
        return out;
    }

    @RequestMapping("/at/cmm/emp/selectList.do")
    public PlatformData empList(PlatformData in) {
        PlatformData out = new PlatformData();
        out.addVariable("list", sqlSession.selectList("kr.go.at.cmm.AtCmmMapper.selectEmpList", in));
        return out;
    }
}
