package kr.go.hr.edu.service;

import java.util.List;

import kr.go.cmm.vo.BaseVO;

public class EduVO extends BaseVO {
    private String eduSeq;
    private String empNo;
    private String eduTypeCd;
    private String eduNm;
    private String eduYear;
    private int eduHours;
    private String instNm;
    private List<EduVO> rows;

    public String getEduSeq() { return eduSeq; }
    public void setEduSeq(String eduSeq) { this.eduSeq = eduSeq; }
    public String getEmpNo() { return empNo; }
    public void setEmpNo(String empNo) { this.empNo = empNo; }
    public String getEduTypeCd() { return eduTypeCd; }
    public void setEduTypeCd(String eduTypeCd) { this.eduTypeCd = eduTypeCd; }
    public String getEduNm() { return eduNm; }
    public void setEduNm(String eduNm) { this.eduNm = eduNm; }
    public String getEduYear() { return eduYear; }
    public void setEduYear(String eduYear) { this.eduYear = eduYear; }
    public int getEduHours() { return eduHours; }
    public void setEduHours(int eduHours) { this.eduHours = eduHours; }
    public String getInstNm() { return instNm; }
    public void setInstNm(String instNm) { this.instNm = instNm; }
    public List<EduVO> getRows() { return rows; }
    public void setRows(List<EduVO> rows) { this.rows = rows; }
}
