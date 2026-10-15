// elkjs 자동 레이아웃 실행기 — 웹 워커에서 계산해 300 테이블 규모에서도 화면이 멈추지 않게 한다 (performance-optimization).
// 워커 스크립트는 번들 자산(?url)으로 함께 배포된다 → 폐쇄망에서도 외부 호출 없음.
import type { ELK } from 'elkjs/lib/elk-api'
import type { ElkInput, ElkOutput } from '@/utils/graph'

let elkPromise: Promise<ELK> | null = null

const createElk = async (): Promise<ELK> => {
  const [{ default: ElkConstructor }, { default: workerUrl }] = await Promise.all([
    import('elkjs/lib/elk-api'),
    import('elkjs/lib/elk-worker.min.js?url'),
  ])
  return new ElkConstructor({ workerFactory: () => new Worker(workerUrl) })
}

export const runElkLayout = async (graph: ElkInput): Promise<ElkOutput> => {
  elkPromise ??= createElk()
  const elk = await elkPromise
  return elk.layout(graph) as Promise<ElkOutput>
}
