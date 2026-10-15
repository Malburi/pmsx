package kr.go.bw.std.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.go.bw.std.service.impl.WelfareStdService;
import lombok.RequiredArgsConstructor;

/** 복지포인트 기준 — 생성자 주입(@RequiredArgsConstructor) */
@RestController
@RequestMapping("/bw/std")
@RequiredArgsConstructor
public class WelfareStdController {

    private final WelfareStdService welfareStdService;

    @PostMapping("/selectStd.do")
    public Map<String, Object> selectStd(@RequestBody Map<String, Object> param) {
        return Map.of("dlt_std", welfareStdService.selectStd(param));
    }

    @PostMapping("/selectLimit.do")
    public Map<String, Object> selectLimit(@RequestBody Map<String, Object> param) {
        return Map.of("dlt_limit", welfareStdService.selectLimit(param));
    }

    @PostMapping("/saveStd.do")
    public Map<String, Object> saveStd(@RequestBody Map<String, Object> param) {
        welfareStdService.saveStd((List<Map<String, Object>>) param.get("dlt_std"));
        return Map.of("result", "OK");
    }

    @PostMapping("/copyYear.do")
    public Map<String, Object> copyYear(@RequestBody Map<String, Object> param) {
        welfareStdService.copyYear(param);
        return Map.of("result", "OK");
    }
}
