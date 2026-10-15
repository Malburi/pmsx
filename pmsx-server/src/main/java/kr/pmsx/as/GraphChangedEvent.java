package kr.pmsx.as;

/** 시스템의 As-Is 그래프(요소·엣지·노드)가 바뀌었음 — 수집 적재, 수동 보정 후 발행. 캐시 무효화용. */
public record GraphChangedEvent(String systemId) {
}
