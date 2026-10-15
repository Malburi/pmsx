package kr.go.bw.std.service.impl;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WelfareStdMapper {
    List<Map<String, Object>> selectStd(Map<String, Object> param);

    List<Map<String, Object>> selectLimit(Map<String, Object> param);

    int mergeStd(Map<String, Object> row);

    void callCopyYear(Map<String, Object> param);
}
