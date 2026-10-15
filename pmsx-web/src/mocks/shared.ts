// mock adapter 공통 타입·헬퍼 (index.ts 와 routes/* 가 함께 쓴다)
import type { InternalAxiosRequestConfig } from 'axios'
import type { MenuNode } from '@/types/api'
import type { Page } from '@/types/common'

export type MockResult = [number, unknown]

export type Handler = (ctx: {
  params: string[]
  config: InternalAxiosRequestConfig
  body: any // eslint-disable-line @typescript-eslint/no-explicit-any -- 요청 본문은 경로마다 형태가 달라 핸들러가 직접 해석한다
}) => MockResult | Promise<MockResult>

export type Route = [method: string, path: RegExp, handler: Handler]

export const err = (status: number, code: string, message: string, fields?: Record<string, string>): MockResult =>
  [status, fields ? { code, message, fields } : { code, message }]

/** 서버와 같은 페이지 응답을 만든다 (page 는 0부터) */
export const paginate = <T>(list: T[], params: Record<string, unknown> | undefined): Page<T> => {
  const page = Math.max(0, Number(params?.page ?? 0) || 0)
  const size = Math.min(200, Math.max(1, Number(params?.size ?? 20) || 20))
  return { content: list.slice(page * size, page * size + size), page, size, totalElements: list.length }
}

/** mock 토큰에서 로그인 아이디를 꺼낸다 */
export const tokenUser = (config: InternalAxiosRequestConfig): string =>
  String(config.headers?.Authorization ?? '').replace('Bearer mock-token-', '')

/** 이 경로가 처리하지 않고 다음으로 일치하는 경로에 넘긴다는 표시 */
export const MOCK_NEXT: MockResult = [-1, null]

/** 메뉴 트리 깊이 우선 순회 (path = 루트부터 이름) */
export const walkMenu = (nodes: MenuNode[], path: string[], visit: (n: MenuNode, path: string[]) => void) => {
  nodes.forEach((n) => {
    const p = [...path, n.name]
    visit(n, p)
    walkMenu(n.children, p, visit)
  })
}

let idSeq = 1000
export const mockId = (prefix: string) => `${prefix}-${++idSeq}`
