package kr.go.cmm.vo;

import java.io.Serializable;

/**
 * 공통 VO - 그리드 행 상태(I/U/D), 등록자 등
 */
public class BaseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String rowStatus;
    private String regId;
    private String regDt;

    public String getRowStatus() { return rowStatus; }
    public void setRowStatus(String rowStatus) { this.rowStatus = rowStatus; }
    public String getRegId() { return regId; }
    public void setRegId(String regId) { this.regId = regId; }
    public String getRegDt() { return regDt; }
    public void setRegDt(String regDt) { this.regDt = regDt; }
}
