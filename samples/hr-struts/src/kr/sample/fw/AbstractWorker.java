package kr.sample.fw;

/** 자체 프레임워크 서비스 기반 클래스 (가상). */
public abstract class AbstractWorker {
    protected DataAccesser dataAccesser;

    protected Object subCall(String bean, String method, Object param) {
        return null;
    }
}
