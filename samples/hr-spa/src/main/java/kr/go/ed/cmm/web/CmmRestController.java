package kr.go.ed.cmm.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import kr.go.ed.cmm.service.CmmService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/cmm")
@RequiredArgsConstructor
public class CmmRestController {

    private final CmmService cmmService;

    @GetMapping("/depts")
    public List<Map<String, Object>> depts() {
        return cmmService.selectDeptList();
    }
}
