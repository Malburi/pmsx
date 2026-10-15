import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    layout?: string
    requiresAuth?: boolean
    roles?: string[]
    /** 넓은 작업 화면(뷰어 등)에서 LNB 를 기본 접힘(rail)으로 시작 */
    compactNav?: boolean
  }
}

export {}
