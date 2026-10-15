package kr.go.ed.stat.service.impl;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StatMapper {
    List<Map<String, Object>> selectEduStat(String year);
}
