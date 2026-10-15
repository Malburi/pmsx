package kr.go.hr.emp.service;

import java.io.Serializable;
import java.util.List;

/**
 * 사원 저장 요청 VO (그리드 변경 행 목록)
 */
public class EmpSaveVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<EmpVO> rows;

    public List<EmpVO> getRows() { return rows; }
    public void setRows(List<EmpVO> rows) { this.rows = rows; }
}
