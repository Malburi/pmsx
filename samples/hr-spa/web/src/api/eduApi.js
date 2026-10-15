import http from './http'

const COURSE = '/edu/courses'

export const getCoursesApi = (params) => http.get(COURSE, { params })
export const getCourseApi = (courseId) => http.get(`${COURSE}/${courseId}`)
export const saveCourseApi = (course) =>
  course.courseId ? http.put(`${COURSE}/${course.courseId}`, course) : http.post(COURSE, course)
export function deleteCourseApi(courseId) {
  return http.delete(`/edu/courses/${courseId}`)
}
export const downloadCoursesExcelApi = (year) => http.get('/edu/courses/excel', { params: { year }, responseType: 'blob' })

export const getAppliesApi = (params) => http.get('/edu/applies', { params })
export const applyCourseApi = (data) => http.post('/edu/applies', data)
export const cancelApplyApi = (applyId) => http.post('/edu/applies/' + applyId + '/cancel')
