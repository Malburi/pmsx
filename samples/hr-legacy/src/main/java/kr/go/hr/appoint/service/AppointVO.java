package kr.go.hr.appoint.service;

import kr.go.cmm.vo.BaseVO;

public class AppointVO extends BaseVO {

    private static final long serialVersionUID = 1L;

    private String apptNo;
    private String empNo;
    private String empNm;
    private String apptType;
    private String fromDeptCd;
    private String toDeptCd;
    private String apptDt;
    private String status;
    private String apptFrom;
    private String apptTo;

    public String getApptNo() { return apptNo; }
    public void setApptNo(String apptNo) { this.apptNo = apptNo; }
    public String getEmpNo() { return empNo; }
    public void setEmpNo(String empNo) { this.empNo = empNo; }
    public String getEmpNm() { return empNm; }
    public void setEmpNm(String empNm) { this.empNm = empNm; }
    public String getApptType() { return apptType; }
    public void setApptType(String apptType) { this.apptType = apptType; }
    public String getFromDeptCd() { return fromDeptCd; }
    public void setFromDeptCd(String fromDeptCd) { this.fromDeptCd = fromDeptCd; }
    public String getToDeptCd() { return toDeptCd; }
    public void setToDeptCd(String toDeptCd) { this.toDeptCd = toDeptCd; }
    public String getApptDt() { return apptDt; }
    public void setApptDt(String apptDt) { this.apptDt = apptDt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getApptFrom() { return apptFrom; }
    public void setApptFrom(String apptFrom) { this.apptFrom = apptFrom; }
    public String getApptTo() { return apptTo; }
    public void setApptTo(String apptTo) { this.apptTo = apptTo; }
}
