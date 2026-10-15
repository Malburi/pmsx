package kr.go.ed.apply.service.impl;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApplyMapper {
    List<Map<String, Object>> selectApplyList(Map<String, Object> cond);
    List<Map<String, Object>> selectMyApplyList();
    int insertApply(Map<String, Object> data);
    int updateApplyStatus(String applyId);
}
