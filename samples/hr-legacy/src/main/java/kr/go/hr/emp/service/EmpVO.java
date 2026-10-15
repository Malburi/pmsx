package kr.go.hr.emp.service;

import kr.go.cmm.vo.BaseVO;

/**
 * 사원 VO
 */
public class EmpVO extends BaseVO {

    private static final long serialVersionUID = 1L;

    private String empNo;
    private String empNm;
    private String deptCd;
    private String deptNm;
    private String posCd;
    private String posNm;
    private String hireDt;
    private String email;
    private String useYn;
    private String hireFrom;
    private String hireTo;

    public String getEmpNo() { return empNo; }
    public void setEmpNo(String empNo) { this.empNo = empNo; }
    public String getEmpNm() { return empNm; }
    public void setEmpNm(String empNm) { this.empNm = empNm; }
    public String getDeptCd() { return deptCd; }
    public void setDeptCd(String deptCd) { this.deptCd = deptCd; }
    public String getDeptNm() { return deptNm; }
    public void setDeptNm(String deptNm) { this.deptNm = deptNm; }
    public String getPosCd() { return posCd; }
    public void setPosCd(String posCd) { this.posCd = posCd; }
    public String getPosNm() { return posNm; }
    public void setPosNm(String posNm) { this.posNm = posNm; }
    public String getHireDt() { return hireDt; }
    public void setHireDt(String hireDt) { this.hireDt = hireDt; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getUseYn() { return useYn; }
    public void setUseYn(String useYn) { this.useYn = useYn; }
    public String getHireFrom() { return hireFrom; }
    public void setHireFrom(String hireFrom) { this.hireFrom = hireFrom; }
    public String getHireTo() { return hireTo; }
    public void setHireTo(String hireTo) { this.hireTo = hireTo; }
}
