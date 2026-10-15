// 목록 표 열을 자리 너비에 맞춰 고른다. 우선순위가 낮은 열에 hideBelow(px)를 주면
// 표가 들어갈 자리가 그보다 좁을 때 그 열을 숨긴다 (목록+상세 2단 화면에서 마지막 열이 잘리는 것 방지).

export type ResponsiveHeader<T extends object = Record<string, unknown>> = T & { key: string; hideBelow?: number }

export const fitColumns = <T extends { key: string; hideBelow?: number }>(headers: T[], width: number): Omit<T, 'hideBelow'>[] =>
  headers
    .filter((h) => !h.hideBelow || width >= h.hideBelow)
    .map(({ hideBelow: _hideBelow, ...rest }) => rest)

/** 숨겨진 열인지 (숨긴 값을 다른 칸에 함께 보여 줄 때 사용) */
export const isColumnHidden = (headers: { key: string; hideBelow?: number }[], width: number, key: string): boolean => {
  const h = headers.find((x) => x.key === key)
  return !!h?.hideBelow && width < h.hideBelow
}
