package kr.go.ed.course.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.go.ed.course.service.CourseService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CourseServiceImpl implements CourseService {

    private final CourseMapper courseMapper;

    @Override
    public List<Map<String, Object>> selectCourseList(Map<String, Object> cond) {
        return courseMapper.selectCourseList(cond);
    }

    @Override
    public byte[] makeCourseExcel(String year) {
        Map<String, Object> cond = new HashMap<>();
        cond.put("year", year);
        List<Map<String, Object>> list = courseMapper.selectCourseList(cond);
        return new byte[list.size()];
    }

    @Override
    public Map<String, Object> selectCourse(String courseId) {
        return courseMapper.selectCourse(courseId);
    }

    @Override
    @Transactional
    public void insertCourse(Map<String, Object> course) {
        courseMapper.insertCourse(course);
    }

    @Override
    @Transactional
    public void updateCourse(Map<String, Object> course) {
        courseMapper.updateCourse(course);
    }

    @Override
    @Transactional
    public void deleteCourse(String courseId) {
        if (courseMapper.countApply(courseId) > 0) {
            throw new IllegalStateException("신청자가 있는 과정은 삭제할 수 없습니다.");
        }
        courseMapper.deleteCourse(courseId);
    }
}
