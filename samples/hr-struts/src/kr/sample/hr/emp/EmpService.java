package kr.sample.hr.emp;

import kr.sample.fw.AbstractWorker;

/** 사원 서비스 - method=list → doList, method=save → doSave */
public class EmpService extends AbstractWorker {

    public Object doList(Object param) {
        // 목록 조회
        return dataAccesser.query("EMP_LIST_S01", param);
    }

    public Object doEdit(Object param) {
        Object codes = subCall("CodeService", "codeList", param);
        return dataAccesser.query("EMP_LIST_S01", param);
    }

    public Object doSave(Object param, String cmd) {
        String queryId1 = "EMP_I01";
        if ("U".equals(cmd)) {
            queryId1 = "EMP_U01";
        }
        dataAccesser.update(queryId1, param);
        StringBuffer histId = new StringBuffer("EMP_HIST_I01");
        dataAccesser.update(histId.toString(), param);
        return null;
    }

    public Object doPop(Object param) {
        return dataAccesser.query("EMP_LIST_S01", param);
    }
}
