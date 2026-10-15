package kr.go.ed.cert.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import kr.go.ed.cert.service.CertService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CertServiceImpl implements CertService {

    private final CertMapper certMapper;

    @Override
    public List<Map<String, Object>> selectCertList(Map<String, Object> cond) {
        return certMapper.selectCertList(cond);
    }

    @Override
    public void insertCert(Map<String, Object> cert) {
        certMapper.insertCert(cert);
    }
}
