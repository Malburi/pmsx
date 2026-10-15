import api from '@/utils/axios'
import type { UserLookup } from '@/types/common'

// 멤버 추가·담당자 지정용 사용자 조회 (docs/10 v0.2) — 로그인 사용자면 누구나, 최대 20건
export const lookupUsersApi = (q: string) =>
  api.get<unknown, UserLookup[]>('/api/v1/users/lookup', { params: { q: q.trim() || undefined } })
