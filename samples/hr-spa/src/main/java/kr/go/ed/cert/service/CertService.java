package kr.go.ed.cert.service;

import java.util.List;
import java.util.Map;

public interface CertService {
    List<Map<String, Object>> selectCertList(Map<String, Object> cond);
    void insertCert(Map<String, Object> cert);
}
