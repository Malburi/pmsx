package kr.pmsx.cm.excel;

import java.util.List;
import java.util.Map;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.security.AuthUser;

/**
 * 엑셀 데이터셋 (FR-CM-006, docs/10 §3). 내보내기·가져오기·템플릿 매핑을 같은 정의로 처리한다.
 * 새 대장 화면을 만들 때 이 인터페이스를 구현한 빈을 추가하면 엑셀 기능이 따라온다.
 */
public interface ExcelDataset {

    record Column(String field, String header, boolean required, String type, String codeGroup) {

        public static Column of(String field, String header) {
            return new Column(field, header, false, "string", null);
        }

        public static Column required(String field, String header) {
            return new Column(field, header, true, "string", null);
        }

        public static Column number(String field, String header) {
            return new Column(field, header, false, "number", null);
        }

        public static Column date(String field, String header) {
            return new Column(field, header, false, "date", null);
        }

        public static Column code(String field, String header, boolean required, String codeGroup) {
            return new Column(field, header, required, "code", codeGroup);
        }
    }

    String id();

    String name();

    default boolean importable() {
        return false;
    }

    default List<String> params() {
        return List.of();
    }

    List<Column> columns();

    /** 내보내기·가져오기 권한 확인. write=true 는 가져오기. */
    void checkAccess(AuthUser user, Map<String, String> params, boolean write);

    /** 내보낼 행 (field → 값) */
    List<Map<String, Object>> rows(AuthUser user, Map<String, String> params);

    /** 데이터셋 고유 검증 (기본 검증 이후). field → 오류 메시지 */
    default Map<String, String> validateRow(Map<String, String> values, Map<String, String> params) {
        return Map.of();
    }

    /** 검증을 통과한 행 반영. [inserted, updated] */
    default int[] commit(AuthUser user, List<Map<String, String>> rows, Map<String, String> params) {
        throw ApiException.badRequest("VALIDATION", "가져오기를 지원하지 않는 데이터셋입니다.");
    }

    /** 템플릿 공통 셀 값에 쓸 프로젝트·시스템 이름 등 (선택) */
    default Map<String, String> commonValues(Map<String, String> params) {
        return Map.of();
    }

    static String required(Map<String, String> params, String name) {
        String v = params.get(name);
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest("VALIDATION", "파라미터 " + name + " 가 필요합니다.");
        }
        return v;
    }
}
