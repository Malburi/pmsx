import { getMenuTreeApi } from '@/api/asisApi'
import { getChangeRequestsApi, getDefectsApi, getIssuesApi, getTestScenariosApi } from '@/api/qualityApi'
import { getWbsApi } from '@/api/scheduleApi'
import { getProjectDeliverablesApi, getRequirementsApi } from '@/api/traceApi'
import { useProjectStore } from '@/stores/project'
import type { MenuNode } from '@/types/api'
import type { TraceType } from '@/types/r2'

export interface TraceTarget {
  id: string
  label: string
}

/** 추적 링크 대상 고르기 — 유형별로 후보 목록을 읽는다 (화면은 현재 프로젝트의 모든 시스템 메뉴에서) */
export const LINKABLE_TYPES: TraceType[] = ['SCREEN', 'TC', 'DEFECT', 'CR', 'ISSUE', 'WBS', 'DELIVERABLE', 'REQ']

export const useTraceTargets = () => {
  const store = useProjectStore()
  const cache = new Map<TraceType, TraceTarget[]>()

  const screensOf = (nodes: MenuNode[], out: TraceTarget[]) => {
    nodes.forEach((n) => {
      if (n.screen) out.push({ id: n.screen.screenId, label: `${n.screen.screenCode} ${n.name}` })
      screensOf(n.children, out)
    })
    return out
  }

  const load = async (type: TraceType): Promise<TraceTarget[]> => {
    if (cache.has(type)) return cache.get(type) as TraceTarget[]
    const pid = store.currentProjectId
    if (!pid) return []
    let list: TraceTarget[] = []
    if (type === 'SCREEN') {
      const trees = await Promise.all(store.systems.map((s) => getMenuTreeApi(s.systemId).catch(() => [] as MenuNode[])))
      list = trees.flatMap((tr) => screensOf(tr, []))
    } else if (type === 'TC') {
      list = (await getTestScenariosApi(pid)).flatMap((s) => s.cases.map((c) => ({ id: c.caseId, label: `${c.tcNo} ${c.title}` })))
    } else if (type === 'DEFECT') {
      list = (await getDefectsApi(pid, { size: 200 })).content.map((d) => ({ id: d.defectId, label: `${d.defectNo} ${d.title}` }))
    } else if (type === 'CR') {
      list = (await getChangeRequestsApi(pid)).map((c) => ({ id: c.crId, label: `${c.crNo} ${c.title}` }))
    } else if (type === 'ISSUE') {
      list = (await getIssuesApi(pid, { size: 200 })).content.map((i) => ({ id: i.issueId, label: `${i.issueNo} ${i.title}` }))
    } else if (type === 'WBS') {
      list = (await getWbsApi(pid)).map((w) => ({ id: w.wbsId, label: `${w.wbsNo} ${w.name}` }))
    } else if (type === 'DELIVERABLE') {
      list = (await getProjectDeliverablesApi(pid)).map((d) => ({ id: d.deliverableId, label: `${d.phase} · ${d.name}` }))
    } else if (type === 'REQ') {
      list = (await getRequirementsApi(pid, { size: 200 })).content.map((r) => ({ id: r.reqId, label: `${r.reqNo} ${r.title}` }))
    }
    cache.set(type, list)
    return list
  }

  return { load }
}
