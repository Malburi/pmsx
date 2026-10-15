package kr.go.ed.cert.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import kr.go.ed.cert.service.CertService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/cert")
@RequiredArgsConstructor
public class CertRestController {

    private final CertService certService;

    @GetMapping("/certs")
    public List<Map<String, Object>> list(@RequestParam Map<String, Object> cond) {
        return certService.selectCertList(cond);
    }

    @PostMapping("/certs")
    public void create(@RequestBody Map<String, Object> cert) {
        certService.insertCert(cert);
    }
}
