package kr.go.ed.course.service.impl;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CourseMapper {
    List<Map<String, Object>> selectCourseList(Map<String, Object> cond);
    Map<String, Object> selectCourse(String courseId);
    int insertCourse(Map<String, Object> course);
    int updateCourse(Map<String, Object> course);
    int countApply(String courseId);
    int deleteCourse(String courseId);
}
