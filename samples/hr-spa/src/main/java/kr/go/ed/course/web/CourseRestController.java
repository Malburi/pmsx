package kr.go.ed.course.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import kr.go.ed.course.service.CourseService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/edu")
@RequiredArgsConstructor
public class CourseRestController {

    private final CourseService courseService;

    @GetMapping("/courses")
    public List<Map<String, Object>> list(@RequestParam Map<String, Object> cond) {
        return courseService.selectCourseList(cond);
    }

    @GetMapping("/courses/excel")
    public byte[] excel(@RequestParam("year") String year) {
        return courseService.makeCourseExcel(year);
    }

    @GetMapping("/courses/{courseId}")
    public Map<String, Object> detail(@PathVariable String courseId) {
        return courseService.selectCourse(courseId);
    }

    @PostMapping("/courses")
    public void create(@RequestBody Map<String, Object> course) {
        courseService.insertCourse(course);
    }

    @PutMapping("/courses/{courseId}")
    public void update(@PathVariable String courseId, @RequestBody Map<String, Object> course) {
        courseService.updateCourse(course);
    }

    @DeleteMapping("/courses/{courseId}")
    public void delete(@PathVariable String courseId) {
        courseService.deleteCourse(courseId);
    }
}
