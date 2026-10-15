import { createBrowserRouter } from 'react-router-dom'
import CertList from './pages/CertList'

/** 자격증 관리 (React 로 신규 개발된 화면 묶음) */
export const router = createBrowserRouter([
  {
    path: '/cert',
    element: <CertList />,
    handle: { title: '자격증 관리', screenId: 'ED0301', menuGroup: '자격관리' },
  },
])
