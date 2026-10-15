package kr.sample.hr.code;

import kr.sample.fw.AbstractWorker;

public class CodeService extends AbstractWorker {
    public Object doCodeList(Object param) {
        return dataAccesser.query("CODE_S01,1", param);
    }
}
