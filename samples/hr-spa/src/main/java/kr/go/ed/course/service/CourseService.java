package kr.go.ed.course.service;

import java.util.List;
import java.util.Map;

public interface CourseService {
    List<Map<String, Object>> selectCourseList(Map<String, Object> cond);
    byte[] makeCourseExcel(String year);
    Map<String, Object> selectCourse(String courseId);
    void insertCourse(Map<String, Object> course);
    void updateCourse(Map<String, Object> course);
    void deleteCourse(String courseId);
}
