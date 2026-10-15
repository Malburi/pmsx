package kr.go.cmm.code.service;

import java.io.Serializable;

public class CodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String cd;
    private String cdNm;

    public String getCd() { return cd; }
    public void setCd(String cd) { this.cd = cd; }
    public String getCdNm() { return cdNm; }
    public void setCdNm(String cdNm) { this.cdNm = cdNm; }
}
