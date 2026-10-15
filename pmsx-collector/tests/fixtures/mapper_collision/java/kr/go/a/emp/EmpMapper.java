package kr.go.a.emp;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmpMapper {
    String selectName(String id);
}
