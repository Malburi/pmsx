import { createRouter, createWebHistory } from 'vue-router'
import EduCourseList from '@/views/edu/EduCourseList.vue'

const routes = [
  { path: '/', redirect: '/edu/courses' },
  {
    path: '/edu',
    meta: { title: '교육관리' },
    children: [
      { path: 'courses', name: 'EduCourseList', component: EduCourseList, meta: { title: '교육과정 관리', screenId: 'ED0101', menuOrder: 1 } },
      { path: 'apply', name: 'EduApply', component: () => import('@/views/edu/EduApply.vue'), meta: { title: '수강 신청', screenId: 'ED0102', menuOrder: 2 } },
    ],
  },
  {
    path: '/stat',
    meta: { title: '통계' },
    children: [
      { path: 'edu', name: 'EduStat', component: () => import('@/views/stat/EduStat.vue'), meta: { title: '교육 실적 통계', screenId: 'ED0201' } },
    ],
  },
]

export default createRouter({ history: createWebHistory(), routes })
