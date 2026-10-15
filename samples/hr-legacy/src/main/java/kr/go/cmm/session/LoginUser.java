package kr.go.cmm.session;

import java.io.Serializable;

/**
 * 세션 로그인 사용자 (ArgumentResolver 로 주입)
 */
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userId;
    private String userNm;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUserNm() { return userNm; }
    public void setUserNm(String userNm) { this.userNm = userNm; }
}
