import http from './http'

/** 공통코드 API (기본 내보내기 객체 형태) */
export default {
  getDeptList() {
    return http.get('/cmm/depts')
  },
}
