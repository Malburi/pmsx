package kr.go.bw.cmm.web;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import egovframework.rte.psl.dataaccess.EgovAbstractMapper;

/** 공통코드·사원검색 — DAO 없이 SqlSession 문자열 호출(selectList("ns.id")) */
@RestController
public class BwCmmController {

    @Resource(name = "sqlSession")
    private EgovAbstractMapper sqlSession;

    @PostMapping("/bw/cmm/code/list.do")
    public Map<String, Object> codeList(@RequestBody Map<String, Object> param) {
        List<Map<String, Object>> list = sqlSession.selectList("kr.go.bw.cmm.BwCmmMapper.selectCodeList", param);
        return Map.of("list", list);
    }

    @PostMapping("/bw/cmm/emp/selectList.do")
    public Map<String, Object> empList(@RequestBody Map<String, Object> param) {
        return Map.of("dlt_emp", sqlSession.selectList("kr.go.bw.cmm.BwCmmMapper.selectEmpList", param));
    }
}
