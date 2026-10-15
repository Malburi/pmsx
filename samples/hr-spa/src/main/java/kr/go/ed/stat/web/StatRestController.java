package kr.go.ed.stat.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import kr.go.ed.stat.service.StatService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/stat")
@RequiredArgsConstructor
public class StatRestController {

    private final StatService statService;

    @GetMapping("/edu")
    public List<Map<String, Object>> edu(@RequestParam("year") String year) {
        return statService.selectEduStat(year);
    }
}
