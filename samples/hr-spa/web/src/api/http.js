import axios from 'axios'

/** 공통 axios 인스턴스 — 모든 API 는 /api 하위 */
const http = axios.create({ baseURL: '/api', timeout: 10000 })
http.interceptors.response.use((res) => res.data)

export default http
