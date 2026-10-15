package kr.go.ed.cmm.service.impl;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CmmMapper {
    List<Map<String, Object>> selectDeptList();
}
