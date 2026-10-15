package kr.go.hr.dept.service;

import kr.go.cmm.vo.BaseVO;

public class DeptVO extends BaseVO {

    private static final long serialVersionUID = 1L;

    private String deptCd;
    private String deptNm;
    private String upDeptCd;
    private String upDeptNm;
    private Integer sortOrd;
    private String useYn;

    public String getDeptCd() { return deptCd; }
    public void setDeptCd(String deptCd) { this.deptCd = deptCd; }
    public String getDeptNm() { return deptNm; }
    public void setDeptNm(String deptNm) { this.deptNm = deptNm; }
    public String getUpDeptCd() { return upDeptCd; }
    public void setUpDeptCd(String upDeptCd) { this.upDeptCd = upDeptCd; }
    public String getUpDeptNm() { return upDeptNm; }
    public void setUpDeptNm(String upDeptNm) { this.upDeptNm = upDeptNm; }
    public Integer getSortOrd() { return sortOrd; }
    public void setSortOrd(Integer sortOrd) { this.sortOrd = sortOrd; }
    public String getUseYn() { return useYn; }
    public void setUseYn(String useYn) { this.useYn = useYn; }
}
