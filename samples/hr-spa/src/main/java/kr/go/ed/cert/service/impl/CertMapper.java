package kr.go.ed.cert.service.impl;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CertMapper {
    List<Map<String, Object>> selectCertList(Map<String, Object> cond);
    int insertCert(Map<String, Object> cert);
}
