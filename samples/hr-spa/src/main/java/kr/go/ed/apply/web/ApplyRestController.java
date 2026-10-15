package kr.go.ed.apply.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import kr.go.ed.apply.service.ApplyService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/edu")
@RequiredArgsConstructor
public class ApplyRestController {

    private final ApplyService applyService;

    @GetMapping("/applies")
    public List<Map<String, Object>> list(@RequestParam Map<String, Object> cond) {
        return applyService.selectApplyList(cond);
    }

    @GetMapping("/my-applies")
    public List<Map<String, Object>> mine() {
        return applyService.selectMyApplyList();
    }

    @PostMapping("/applies")
    public void apply(@RequestBody Map<String, Object> data) {
        applyService.insertApply(data);
    }

    @PostMapping("/applies/{applyId}/cancel")
    public void cancel(@PathVariable String applyId) {
        applyService.cancelApply(applyId);
    }
}
