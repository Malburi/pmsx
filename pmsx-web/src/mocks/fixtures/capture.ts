// HR0101 캡처 이미지 대용 SVG (실제 운영에서는 수집기가 찍은 PNG). 좌표는 HR0101_ELEMENTS 의 box 와 일치한다.
import { CAPTURE_H, CAPTURE_W } from './hr0101'

const rows = [
  ['20190012', '홍길동', '인사팀', '과장', '2019-03-02'],
  ['20200105', '김영희', '재무팀', '대리', '2020-07-01'],
  ['20210033', '이철수', '정보화팀', '주임', '2021-01-04'],
  ['20220218', '박민수', '인사팀', '사원', '2022-09-15'],
  ['20230007', '최지은', '총무팀', '사원', '2023-02-01'],
  ['20240121', '정하늘', '정보화팀', '사원', '2024-07-08'],
]

const btn = (x: number, y: number, w: number, label: string) =>
  `<rect x="${x}" y="${y}" width="${w}" height="24" rx="2" fill="url(#g)" stroke="#7d889a"/>` +
  `<text x="${x + w / 2}" y="${y + 16}" text-anchor="middle">${label}</text>`

const input = (x: number, y: number, w: number, text = '') =>
  `<rect x="${x}" y="${y}" width="${w}" height="24" fill="#fff" stroke="#8a94a6"/>` +
  `<text x="${x + 5}" y="${y + 16}" fill="#555">${text}</text>`

const cols = [16, 66, 196, 326, 486, 606, 884]
const heads = ['No', '사원번호', '사원명', '부서', '직위', '입사일']

const grid = () => {
  let s = `<rect x="16" y="148" width="868" height="360" fill="#fff" stroke="#8a94a6"/>`
  s += `<rect x="16" y="148" width="868" height="24" fill="#c9d2df"/>`
  heads.forEach((h, i) => { s += `<text x="${(cols[i] + cols[i + 1]) / 2}" y="164" text-anchor="middle">${h}</text>` })
  rows.forEach((r, ri) => {
    const y = 172 + ri * 24
    if (ri % 2 === 0) s += `<rect x="17" y="${y}" width="866" height="24" fill="#f5f7fa"/>`
    const cells = [String(ri + 1), ...r]
    cells.forEach((c, ci) => { s += `<text x="${(cols[ci] + cols[ci + 1]) / 2}" y="${y + 16}" text-anchor="middle">${c}</text>` })
    s += `<line x1="16" x2="884" y1="${y + 24}" y2="${y + 24}" stroke="#d5dbe4"/>`
  })
  cols.slice(1, -1).forEach((x) => { s += `<line x1="${x}" x2="${x}" y1="148" y2="${172 + rows.length * 24}" stroke="#d5dbe4"/>` })
  return s
}

export const HR0101_CAPTURE_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="${CAPTURE_W}" height="${CAPTURE_H}" viewBox="0 0 ${CAPTURE_W} ${CAPTURE_H}" font-family="Gulim, 'Malgun Gothic', sans-serif" font-size="12" fill="#222">
<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fdfdfd"/><stop offset="1" stop-color="#d7dce4"/></linearGradient></defs>
<rect width="${CAPTURE_W}" height="${CAPTURE_H}" fill="#d9dee7"/>
<rect width="${CAPTURE_W}" height="30" fill="#3a5a8c"/>
<text x="10" y="20" fill="#fff" font-weight="700">■ 사원관리 [HR0101]</text>
<rect x="8" y="38" width="884" height="40" fill="#eef1f5" stroke="#9aa5b5"/>
<text x="40" y="62">사원명</text>${input(90, 46, 150)}${btn(244, 46, 24, '🔍')}
<text x="268" y="62">부서</text>${input(300, 46, 140, '전체 ▼')}
<text x="476" y="62">입사일</text>${input(530, 46, 210, '2019-01-01 ~ 2026-10-14')}
<text x="16" y="100">▶ 사원 목록 (총 6건)</text>
${btn(624, 84, 60, '인쇄')}${btn(690, 84, 60, '조회')}${btn(756, 84, 60, '엑셀')}
${grid()}
${btn(690, 520, 60, '신규')}${btn(756, 520, 60, '저장')}${btn(822, 520, 60, '삭제')}
</svg>`
