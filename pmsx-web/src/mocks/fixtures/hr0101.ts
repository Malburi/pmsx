// mock 모드 픽스처 — docs/mockup/asis-viewer.html 의 HR0101 사원관리 샘플을 API 명세(08) 형태로 옮긴 것.
import type { ElementSummary, NodeDetail, ScreenDetail, Workflow, WorkflowStep } from '@/types/api'

export const CAPTURE_W = 900
export const CAPTURE_H = 560

const el = (
  seqNo: number, code: string, label: string, type: ElementSummary['type'],
  box: [number, number, number, number] | null, binding: string | null,
  events: ElementSummary['events'], extra: Partial<ElementSummary> = {},
): ElementSummary => ({
  elementId: `el-hr0101-${code}`,
  seqNo,
  elementCode: code,
  label,
  type,
  box: box ? { x: box[0], y: box[1], w: box[2], h: box[3] } : null,
  binding,
  matched: 'full',
  danger: false,
  events,
  manual: false,
  hidden: false,
  ...extra,
})

export const HR0101_ELEMENTS: ElementSummary[] = [
  el(1, 'txtEmpNm', '사원명', 'input', [90, 46, 150, 24], '검색조건 empNm', [{ event: 'keydown(Enter)', handler: 'fn_search()' }]),
  el(2, 'cboDept', '부서', 'combo', [300, 46, 140, 24], '검색조건 deptCd · 코드 TB_DEPT', [{ event: 'onload', handler: 'fn_initCombo()' }]),
  el(3, 'calHireDt', '입사일(기간)', 'date', [530, 46, 210, 24], '검색조건 hireFrom ~ hireTo', []),
  el(4, 'btnSearch', '조회', 'button', [690, 84, 60, 24], null, [{ event: 'click', handler: 'fn_search()' }]),
  el(5, 'btnNew', '신규', 'button', [690, 520, 60, 24], null, [{ event: 'click', handler: 'fn_new()' }]),
  el(6, 'btnSave', '저장', 'button', [756, 520, 60, 24], null, [{ event: 'click', handler: 'fn_save()' }]),
  el(7, 'btnDelete', '삭제', 'button', [822, 520, 60, 24], null, [{ event: 'click', handler: 'fn_delete()' }], { danger: true }),
  el(8, 'btnExcel', '엑셀', 'button', [756, 84, 60, 24], null, [{ event: 'click', handler: 'fn_excel()' }]),
  el(9, 'btnEmpPop', '사원찾기', 'popup', [244, 46, 24, 24], null, [{ event: 'click', handler: 'fn_openEmpPopup()' }]),
  el(10, 'grdEmp', '사원 목록 그리드', 'grid', [16, 148, 868, 360], 'ds_emp: EMP_NO, EMP_NM, DEPT_NM, POS_NM, HIRE_DT', [{ event: 'dblclick', handler: 'fn_grdDblClick()' }]),
  el(11, 'btnPrint', '인쇄', 'button', [624, 84, 60, 24], null, [], { matched: 'dynamic-only' }),
  el(12, 'hdnEmpNo', '선택 사원번호(hidden)', 'input', null, '검색조건 empNo', [], { matched: 'static-only' }),
]

export const HR0101_DETAIL: ScreenDetail = {
  screenId: 'scr-hr0101',
  screenCode: 'HR0101',
  name: '사원관리',
  kind: 'page',
  uiTech: 'jsp-jquery',
  sourceFile: '/WEB-INF/jsp/hr/emp/HR0101.jsp',
  menuPath: ['인사시스템', '인사관리', '사원관리'],
  analysis: {
    status: 'REVIEW',
    owner: { userId: 'u-analyst', name: '김분석' },
    reviewer: { userId: 'u-pl', name: '이PL' },
    updatedAt: '2026-10-05T14:20:00+09:00',
  },
  capture: { captureId: 'cap-hr0101-3', width: CAPTURE_W, height: CAPTURE_H, collectedAt: '2026-10-02T10:12:00+09:00', runSeq: 3 },
  elements: HR0101_ELEMENTS,
}

/* ── 워크플로우 ────────────────────────────────────────────── */
type S = Partial<WorkflowStep> & Pick<WorkflowStep, 'lane' | 'title' | 'depth'>
const st = (s: S): WorkflowStep => ({
  nodeId: null, subtitle: null, crud: null, columns: null, hasSource: false, edge: null, ...s,
})
let edgeSeq = 0
const edge = (kind: string, source: 'static' | 'dynamic' | 'manual', confidence: number, condition: string | null = null, evidence: string | null = null) =>
  ({ edgeId: `edge-hr0101-${++edgeSeq}`, kind, source, confidence, condition, evidence, confirmed: false, status: 'active' as const })

const searchSteps = (depth0: number): WorkflowStep[] => [
  st({ lane: 'API', title: 'POST /hr/emp/list.do', subtitle: 'param: empNm, deptCd, hireFrom, hireTo', depth: depth0, nodeId: 'nd-api-list', edge: edge('request', 'dynamic', 1, null, '관측 요청 2026-10-02 10:12') }),
  st({ lane: 'SERVER', title: 'EmpController.selectEmpList', subtitle: 'EmpController.java:41', depth: depth0 + 1, nodeId: 'nd-java-ctl-list', hasSource: true, edge: edge('handles', 'static', 0.99, null, 'EmpController.java:41') }),
  st({ lane: 'SERVER', title: 'EmpServiceImpl.selectEmpList', subtitle: 'EmpServiceImpl.java:30', depth: depth0 + 2, nodeId: 'nd-java-svc-list', edge: edge('calls', 'static', 0.99) }),
  st({ lane: 'SQL', title: 'EmpMapper.selectEmpList', subtitle: 'SELECT · EmpMapper.xml:8', depth: depth0 + 3, nodeId: 'nd-sql-list', hasSource: true, edge: edge('executes', 'static', 0.99) }),
  st({ lane: 'DB', title: 'TB_EMP', subtitle: 'HR.TB_EMP', depth: depth0 + 4, nodeId: 'nd-tbl-emp', crud: 'R', columns: ['EMP_NO', 'EMP_NM', 'DEPT_CD', 'HIRE_DT', 'POS_CD', 'USE_YN'], edge: edge('accesses', 'static', 0.99) }),
  st({ lane: 'DB', title: 'TB_DEPT', subtitle: 'HR.TB_DEPT', depth: depth0 + 4, nodeId: 'nd-tbl-dept', crud: 'R', columns: ['DEPT_CD', 'DEPT_NM'], edge: edge('accesses', 'static', 0.99) }),
]

export const HR0101_WORKFLOWS: Record<string, Workflow> = {
  'el-hr0101-btnSearch': {
    elementId: 'el-hr0101-btnSearch',
    events: [{
      event: 'click',
      steps: [
        st({ lane: 'UI', title: '[조회] 클릭', subtitle: 'btnSearch · click', depth: 0 }),
        st({ lane: 'FRONT', title: 'fn_search()', subtitle: '/js/hr/HR0101.js:20', depth: 1, nodeId: 'nd-js-fn_search', hasSource: true, edge: edge('event', 'static', 0.99, null, 'HR0101.js:5') }),
        ...searchSteps(2),
        st({ lane: 'POST', title: '그리드 바인딩 · 건수 표시', subtitle: 'grdEmp ← res.list', depth: 2, edge: edge('calls', 'static', 0.9, '성공 콜백') }),
      ],
    }],
  },
  'el-hr0101-txtEmpNm': {
    elementId: 'el-hr0101-txtEmpNm',
    events: [{
      event: 'keydown(Enter)',
      steps: [
        st({ lane: 'UI', title: '사원명 Enter', subtitle: 'txtEmpNm · keydown', depth: 0 }),
        st({ lane: 'FRONT', title: 'fn_search()', subtitle: '조회 버튼과 동일 흐름', depth: 1, nodeId: 'nd-js-fn_search', hasSource: true, edge: edge('event', 'static', 0.97, "e.keyCode == 13") }),
      ],
    }],
  },
  'el-hr0101-cboDept': {
    elementId: 'el-hr0101-cboDept',
    events: [{
      event: 'onload',
      steps: [
        st({ lane: 'UI', title: '화면 로드', subtitle: '$(document).ready', depth: 0 }),
        st({ lane: 'FRONT', title: 'fn_initCombo()', subtitle: '/js/hr/HR0101.js:12', depth: 1, nodeId: 'nd-js-fn_initCombo', hasSource: true, edge: edge('event', 'static', 0.98) }),
        st({ lane: 'FRONT', title: 'gfn_setCombo()', subtitle: '공통 함수 · /js/cmm/common.js', depth: 2, nodeId: 'nd-js-gfn_setCombo', edge: edge('calls', 'static', 0.98) }),
        st({ lane: 'API', title: 'GET /cmm/code/dept.do', subtitle: null, depth: 2, nodeId: 'nd-api-dept', edge: edge('request', 'dynamic', 1) }),
        st({ lane: 'SERVER', title: 'CmmCodeController.selectDeptList', subtitle: null, depth: 3, nodeId: 'nd-java-code-ctl', edge: edge('handles', 'static', 0.95) }),
        st({ lane: 'SQL', title: 'CmmCodeMapper.selectDeptList', subtitle: 'SELECT', depth: 4, nodeId: 'nd-sql-dept', edge: edge('executes', 'static', 0.95) }),
        st({ lane: 'DB', title: 'TB_DEPT', subtitle: 'HR.TB_DEPT', depth: 5, nodeId: 'nd-tbl-dept', crud: 'R', columns: ['DEPT_CD', 'DEPT_NM', 'USE_YN'], edge: edge('accesses', 'static', 0.95) }),
      ],
    }],
  },
  'el-hr0101-btnNew': {
    elementId: 'el-hr0101-btnNew',
    events: [{
      event: 'click',
      steps: [
        st({ lane: 'UI', title: '[신규] 클릭', subtitle: 'btnNew · click', depth: 0 }),
        st({ lane: 'FRONT', title: 'fn_new()', subtitle: '/js/hr/HR0101.js:31', depth: 1, nodeId: 'nd-js-fn_new', hasSource: true, edge: edge('event', 'static', 0.99) }),
        st({ lane: 'POST', title: '그리드 행 추가', subtitle: '서버 호출 없음 · rowStatus = I', depth: 2, edge: edge('calls', 'static', 0.95) }),
      ],
    }],
  },
  'el-hr0101-btnSave': {
    elementId: 'el-hr0101-btnSave',
    events: [{
      event: 'click',
      steps: [
        st({ lane: 'UI', title: '[저장] 클릭', subtitle: 'btnSave · click', depth: 0 }),
        st({ lane: 'FRONT', title: 'fn_save()', subtitle: '/js/hr/HR0101.js:36', depth: 1, nodeId: 'nd-js-fn_save', hasSource: true, edge: edge('event', 'static', 0.99, null, 'HR0101.js:6') }),
        st({ lane: 'FRONT', title: 'gfn_validate()', subtitle: '공통 함수 · 필수값: 사원명, 부서, 입사일', depth: 2, nodeId: 'nd-js-gfn_validate', edge: edge('calls', 'static', 0.95) }),
        st({ lane: 'API', title: 'POST /hr/emp/save.do', subtitle: 'rows[] (rowStatus I/U) · 수집 시 차단됨', depth: 2, nodeId: 'nd-api-save', edge: edge('request', 'dynamic', 1, "confirm('저장하시겠습니까?') 확인 시", '관측 후 차단 2026-10-02 10:13') }),
        st({ lane: 'SERVER', title: 'EmpController.saveEmp', subtitle: 'EmpController.java:52', depth: 3, nodeId: 'nd-java-ctl-save', edge: edge('handles', 'static', 0.99) }),
        st({ lane: 'SERVER', title: 'EmpServiceImpl.saveEmp', subtitle: '@Transactional · EmpServiceImpl.java:58', depth: 4, nodeId: 'nd-java-svc-save', hasSource: true, edge: edge('calls', 'static', 0.99) }),
        st({ lane: 'SQL', title: 'EmpMapper.insertEmp', subtitle: 'INSERT · EmpMapper.xml:30', depth: 5, nodeId: 'nd-sql-insert', hasSource: true, edge: edge('executes', 'static', 0.99, "rowStatus == 'I'") }),
        st({ lane: 'DB', title: 'TB_EMP', subtitle: 'HR.TB_EMP', depth: 6, nodeId: 'nd-tbl-emp', crud: 'C', columns: ['EMP_NO', 'EMP_NM', 'DEPT_CD', 'HIRE_DT', 'POS_CD'], edge: edge('accesses', 'static', 0.99) }),
        st({ lane: 'SQL', title: 'EmpMapper.updateEmp', subtitle: 'UPDATE · EmpMapper.xml:44', depth: 5, nodeId: 'nd-sql-update', edge: edge('executes', 'static', 0.99, "rowStatus == 'U'") }),
        st({ lane: 'DB', title: 'TB_EMP', subtitle: 'HR.TB_EMP', depth: 6, nodeId: 'nd-tbl-emp', crud: 'U', columns: ['EMP_NM', 'DEPT_CD', 'HIRE_DT', 'POS_CD'], edge: edge('accesses', 'static', 0.99) }),
        st({ lane: 'SQL', title: 'EmpMapper.insertEmpHist', subtitle: 'INSERT · 변경 이력', depth: 5, nodeId: 'nd-sql-hist', edge: edge('executes', 'static', 0.99) }),
        st({ lane: 'DB', title: 'TB_EMP_HIST', subtitle: 'HR.TB_EMP_HIST', depth: 6, nodeId: 'nd-tbl-emp-hist', crud: 'C', columns: ['EMP_NO', 'CHG_DT', 'CHG_USER', 'BEFORE_JSON'], edge: edge('accesses', 'static', 0.99) }),
        st({ lane: 'POST', title: 'fn_search()', subtitle: '저장 성공 후 재조회 (조회 버튼과 동일 흐름)', depth: 2, nodeId: 'nd-js-fn_search', hasSource: true, edge: edge('calls', 'static', 0.9, '성공 콜백') }),
      ],
    }],
  },
  'el-hr0101-btnDelete': {
    elementId: 'el-hr0101-btnDelete',
    events: [{
      event: 'click',
      steps: [
        st({ lane: 'UI', title: '[삭제] 클릭', subtitle: 'btnDelete · 위험 버튼(동적 클릭 제외)', depth: 0 }),
        st({ lane: 'FRONT', title: 'fn_delete()', subtitle: '/js/hr/HR0101.js:52', depth: 1, nodeId: 'nd-js-fn_delete', hasSource: true, edge: edge('event', 'static', 0.99) }),
        st({ lane: 'API', title: 'POST /hr/emp/delete.do', subtitle: '정적 분석으로만 추정 (클릭 안 함)', depth: 2, nodeId: 'nd-api-delete', edge: edge('request', 'static', 0.8, "confirm('삭제하시겠습니까?') 확인 시", 'HR0101.js:56 문자열 URL') }),
        st({ lane: 'SERVER', title: 'EmpController.deleteEmp', subtitle: null, depth: 3, nodeId: 'nd-java-ctl-delete', edge: edge('handles', 'static', 0.95) }),
        st({ lane: 'SQL', title: 'EmpMapper.deleteEmp', subtitle: "UPDATE … SET USE_YN = 'N' (논리 삭제)", depth: 4, nodeId: 'nd-sql-delete', hasSource: true, edge: edge('executes', 'static', 0.95) }),
        st({ lane: 'DB', title: 'TB_EMP', subtitle: 'HR.TB_EMP', depth: 5, nodeId: 'nd-tbl-emp', crud: 'U', columns: ['USE_YN', 'UPD_DT'], edge: edge('accesses', 'static', 0.95) }),
        st({ lane: 'POST', title: 'fn_search()', subtitle: '재조회', depth: 2, nodeId: 'nd-js-fn_search', hasSource: true, edge: edge('calls', 'static', 0.9, '성공 콜백') }),
      ],
    }],
  },
  'el-hr0101-btnExcel': {
    elementId: 'el-hr0101-btnExcel',
    events: [{
      event: 'click',
      steps: [
        st({ lane: 'UI', title: '[엑셀] 클릭', subtitle: 'btnExcel · click', depth: 0 }),
        st({ lane: 'FRONT', title: 'fn_excel()', subtitle: '/js/hr/HR0101.js:60', depth: 1, nodeId: 'nd-js-fn_excel', edge: edge('event', 'static', 0.99) }),
        st({ lane: 'API', title: 'GET /hr/emp/excel.do', subtitle: null, depth: 2, nodeId: 'nd-api-excel', edge: edge('request', 'dynamic', 1) }),
        st({ lane: 'SERVER', title: 'EmpController.excelEmp', subtitle: 'ExcelView', depth: 3, nodeId: 'nd-java-ctl-excel', edge: edge('handles', 'static', 0.95) }),
        st({ lane: 'SQL', title: 'EmpMapper.selectEmpList', subtitle: '조회와 같은 SQL 재사용', depth: 4, nodeId: 'nd-sql-list', hasSource: true, edge: edge('executes', 'static', 0.95) }),
        st({ lane: 'DB', title: 'TB_EMP', subtitle: 'HR.TB_EMP', depth: 5, nodeId: 'nd-tbl-emp', crud: 'R', columns: null, edge: edge('accesses', 'static', 0.95) }),
        st({ lane: 'DB', title: 'TB_DEPT', subtitle: 'HR.TB_DEPT', depth: 5, nodeId: 'nd-tbl-dept', crud: 'R', columns: null, edge: edge('accesses', 'static', 0.95) }),
      ],
    }],
  },
  'el-hr0101-btnEmpPop': {
    elementId: 'el-hr0101-btnEmpPop',
    events: [{
      event: 'click',
      steps: [
        st({ lane: 'UI', title: '[사원찾기] 클릭', subtitle: 'btnEmpPop · click', depth: 0 }),
        st({ lane: 'FRONT', title: 'fn_openEmpPopup()', subtitle: '/js/hr/HR0101.js:70', depth: 1, nodeId: 'nd-js-fn_openEmpPopup', hasSource: true, edge: edge('event', 'static', 0.99) }),
        st({ lane: 'UI', title: '팝업: HR0101P01 사원검색', subtitle: 'gfn_popup → 화면 이동', depth: 2, nodeId: 'nd-scr-hr0101p01', edge: edge('navigates', 'dynamic', 1) }),
        st({ lane: 'POST', title: '선택값 → 사원명 입력 → 재조회', subtitle: 'fn_search()', depth: 2, nodeId: 'nd-js-fn_search', hasSource: true, edge: edge('calls', 'static', 0.9, '팝업 콜백') }),
      ],
    }],
  },
  'el-hr0101-grdEmp': {
    elementId: 'el-hr0101-grdEmp',
    events: [{
      event: 'dblclick',
      steps: [
        st({ lane: 'UI', title: '행 더블클릭', subtitle: 'grdEmp · dblclick', depth: 0 }),
        st({ lane: 'FRONT', title: 'fn_grdDblClick()', subtitle: '/js/hr/HR0101.js:80', depth: 1, nodeId: 'nd-js-fn_grdDblClick', edge: edge('event', 'static', 0.9) }),
        st({ lane: 'UI', title: '팝업: HR0102 사원상세', subtitle: '분석자가 수동 연결', depth: 2, nodeId: 'nd-scr-hr0102', edge: edge('navigates', 'manual', 1) }),
      ],
    }],
  },
}

/* ── 노드 상세 ─────────────────────────────────────────────── */
const SEL_SQL = `<select id="selectEmpList" resultType="EmpVO">
  SELECT E.EMP_NO, E.EMP_NM, D.DEPT_NM, E.HIRE_DT,
         FN_CODE_NM('POS', E.POS_CD) AS POS_NM
    FROM TB_EMP E
    JOIN TB_DEPT D ON D.DEPT_CD = E.DEPT_CD
   WHERE E.USE_YN = 'Y'
  <if test="empNm != null"> AND E.EMP_NM LIKE '%'||#{empNm}||'%'</if>
  <if test="deptCd != null"> AND E.DEPT_CD = #{deptCd}</if>
   ORDER BY E.EMP_NO
</select>`

const usedByEmp = [
  { screenId: 'scr-hr0101', screenName: '사원관리', elementId: 'el-hr0101-btnSearch', elementLabel: '조회' },
  { screenId: 'scr-hr0101', screenName: '사원관리', elementId: 'el-hr0101-btnSave', elementLabel: '저장' },
  { screenId: 'scr-hr0101', screenName: '사원관리', elementId: 'el-hr0101-btnDelete', elementLabel: '삭제' },
  { screenId: 'scr-hr0101', screenName: '사원관리', elementId: 'el-hr0101-btnExcel', elementLabel: '엑셀' },
  { screenId: 'scr-hr0101p01', screenName: '사원검색 (팝업)', elementId: 'el-hr0101p01-btnSearch', elementLabel: '검색' },
  { screenId: 'scr-hr0103', screenName: '발령관리', elementId: 'el-hr0103-btnSave', elementLabel: '발령 저장' },
]

const node = (n: Partial<NodeDetail> & Pick<NodeDetail, 'nodeId' | 'nodeType' | 'name'>): NodeDetail => ({
  key: n.nodeId, lang: null, file: null, lineStart: null, lineEnd: null, snippet: null, usedBy: [], ...n,
})

export const HR0101_NODES: Record<string, NodeDetail> = {
  'nd-js-fn_search': node({
    nodeId: 'nd-js-fn_search', nodeType: 'FN', name: 'fn_search', key: 'fn:js:/js/hr/HR0101.js#fn_search',
    lang: 'js', file: '/js/hr/HR0101.js', lineStart: 20, lineEnd: 26,
    snippet: `function fn_search(){\n  var param = $('#frmSearch').serializeObject();\n  gfn_ajax('/hr/emp/list.do', param, function(res){\n    gfn_bindGrid('#grdEmp', res.list);\n    $('#totCnt').text(res.list.length);\n  });\n}`,
    usedBy: usedByEmp.slice(0, 2),
  }),
  'nd-js-fn_initCombo': node({
    nodeId: 'nd-js-fn_initCombo', nodeType: 'FN', name: 'fn_initCombo', lang: 'js', file: '/js/hr/HR0101.js', lineStart: 12, lineEnd: 14,
    snippet: `function fn_initCombo(){\n  gfn_setCombo('#cboDept', '/cmm/code/dept.do', {useYn:'Y'}, '전체');\n}`,
  }),
  'nd-js-fn_new': node({
    nodeId: 'nd-js-fn_new', nodeType: 'FN', name: 'fn_new', lang: 'js', file: '/js/hr/HR0101.js', lineStart: 31, lineEnd: 33,
    snippet: `function fn_new(){\n  gfn_addRow('#grdEmp', {useYn:'Y', hireDt: gfn_today()});\n}`,
  }),
  'nd-js-fn_save': node({
    nodeId: 'nd-js-fn_save', nodeType: 'FN', name: 'fn_save', key: 'fn:js:/js/hr/HR0101.js#fn_save',
    lang: 'js', file: '/js/hr/HR0101.js', lineStart: 36, lineEnd: 45,
    snippet: `function fn_save(){\n  var rows = gfn_getChangedRows('#grdEmp');\n  if(rows.length == 0){ alert('변경된 내용이 없습니다.'); return; }\n  if(!gfn_validate(rows, ['empNm','deptCd','hireDt'])) return;\n  if(!confirm('저장하시겠습니까?')) return;\n  gfn_ajax('/hr/emp/save.do', {rows: rows}, function(){\n    alert('저장되었습니다.');\n    fn_search();\n  });\n}`,
    usedBy: [usedByEmp[1]],
  }),
  'nd-js-fn_delete': node({
    nodeId: 'nd-js-fn_delete', nodeType: 'FN', name: 'fn_delete', lang: 'js', file: '/js/hr/HR0101.js', lineStart: 52, lineEnd: 57,
    snippet: `function fn_delete(){\n  var row = gfn_getSelectedRow('#grdEmp');\n  if(!row){ alert('삭제할 사원을 선택하세요.'); return; }\n  if(!confirm('삭제하시겠습니까?')) return;\n  gfn_ajax('/hr/emp/delete.do', {empNo: row.empNo}, fn_search);\n}`,
  }),
  'nd-js-fn_openEmpPopup': node({
    nodeId: 'nd-js-fn_openEmpPopup', nodeType: 'FN', name: 'fn_openEmpPopup', lang: 'js', file: '/js/hr/HR0101.js', lineStart: 70, lineEnd: 75,
    snippet: `function fn_openEmpPopup(){\n  gfn_popup('/hr/emp/HR0101P01.do', 600, 450, function(ret){\n    $('#txtEmpNm').val(ret.empNm);\n    fn_search();\n  });\n}`,
  }),
  'nd-java-ctl-list': node({
    nodeId: 'nd-java-ctl-list', nodeType: 'FN', name: 'EmpController.selectEmpList', lang: 'java',
    file: 'src/main/java/kr/go/hr/emp/web/EmpController.java', lineStart: 41, lineEnd: 45,
    snippet: `@RequestMapping("/hr/emp/list.do")\npublic ModelAndView selectEmpList(@RequestBody EmpVO vo) {\n    List<EmpVO> list = empService.selectEmpList(vo);\n    return new ModelAndView("jsonView", "list", list);\n}`,
  }),
  'nd-java-svc-save': node({
    nodeId: 'nd-java-svc-save', nodeType: 'FN', name: 'EmpServiceImpl.saveEmp', lang: 'java',
    file: 'src/main/java/kr/go/hr/emp/service/impl/EmpServiceImpl.java', lineStart: 58, lineEnd: 68,
    snippet: `@Transactional\npublic void saveEmp(List<EmpVO> rows) {\n    for (EmpVO r : rows) {\n        if ("I".equals(r.getRowStatus())) {\n            empMapper.insertEmp(r);\n        } else if ("U".equals(r.getRowStatus())) {\n            empMapper.updateEmp(r);\n        }\n        empMapper.insertEmpHist(r);   // 변경 이력\n    }\n}`,
  }),
  'nd-sql-list': node({
    nodeId: 'nd-sql-list', nodeType: 'SQL', name: 'EmpMapper.selectEmpList', lang: 'sql',
    file: 'src/main/resources/mapper/hr/EmpMapper.xml', lineStart: 8, lineEnd: 17, snippet: SEL_SQL,
    usedBy: [usedByEmp[0], usedByEmp[3]],
  }),
  'nd-sql-insert': node({
    nodeId: 'nd-sql-insert', nodeType: 'SQL', name: 'EmpMapper.insertEmp', lang: 'sql',
    file: 'src/main/resources/mapper/hr/EmpMapper.xml', lineStart: 30, lineEnd: 36,
    snippet: `<insert id="insertEmp">\n  INSERT INTO TB_EMP (EMP_NO, EMP_NM, DEPT_CD, HIRE_DT, POS_CD, USE_YN, REG_DT)\n  VALUES (#{empNo}, #{empNm}, #{deptCd}, #{hireDt}, #{posCd}, 'Y', SYSDATE)\n</insert>`,
  }),
  'nd-sql-delete': node({
    nodeId: 'nd-sql-delete', nodeType: 'SQL', name: 'EmpMapper.deleteEmp', lang: 'sql',
    file: 'src/main/resources/mapper/hr/EmpMapper.xml', lineStart: 61, lineEnd: 65,
    snippet: `<update id="deleteEmp">\n  UPDATE TB_EMP\n     SET USE_YN = 'N', UPD_DT = SYSDATE\n   WHERE EMP_NO = #{empNo}\n</update>`,
  }),
  'nd-tbl-emp': node({ nodeId: 'nd-tbl-emp', nodeType: 'TABLE', name: 'TB_EMP', key: 'tbl:HR.TB_EMP', usedBy: usedByEmp }),
  'nd-tbl-dept': node({ nodeId: 'nd-tbl-dept', nodeType: 'TABLE', name: 'TB_DEPT', key: 'tbl:HR.TB_DEPT', usedBy: [usedByEmp[0], usedByEmp[3]] }),
  'nd-tbl-emp-hist': node({ nodeId: 'nd-tbl-emp-hist', nodeType: 'TABLE', name: 'TB_EMP_HIST', key: 'tbl:HR.TB_EMP_HIST', usedBy: [usedByEmp[1]] }),
}
