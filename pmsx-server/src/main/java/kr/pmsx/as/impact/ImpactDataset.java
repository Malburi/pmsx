package kr.pmsx.as.impact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.pmsx.cm.excel.ExcelDataset;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.stereotype.Component;

/** 영향도 분석 결과 엑셀 (docs/13 v0.3): params systemId, targetId, column(선택). 행 = 영향 화면·요소 */
@Component
public class ImpactDataset implements ExcelDataset {

    private final ImpactController impact;
    private final ProjectAccess access;

    public ImpactDataset(ImpactController impact, ProjectAccess access) {
        this.impact = impact;
        this.access = access;
    }

    @Override
    public String id() {
        return "impact";
    }

    @Override
    public String name() {
        return "영향도 분석";
    }

    @Override
    public List<String> params() {
        return List.of("systemId", "targetId", "column");
    }

    @Override
    public List<Column> columns() {
        return List.of(Column.of("screenCode", "화면ID"), Column.of("screenName", "화면명"),
                Column.of("menuPath", "메뉴 경로"), Column.of("element", "요소"), Column.of("crud", "CRUD"),
                Column.of("path", "호출 경로"), Column.of("owner", "분석 담당"));
    }

    @Override
    public void checkAccess(AuthUser user, Map<String, String> params, boolean write) {
        if (write) {
            throw ApiException.badRequest("VALIDATION", "가져오기를 지원하지 않는 데이터셋입니다.");
        }
        access.forSystem(user, ExcelDataset.required(params, "systemId"));
    }

    @Override
    public List<Map<String, Object>> rows(AuthUser user, Map<String, String> params) {
        var r = impact.compute(ExcelDataset.required(params, "systemId"), ExcelDataset.required(params, "targetId"),
                params.get("column"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var s : r.screens()) {
            List<ImpactController.ImpactElement> els = s.elements().isEmpty()
                    ? List.of(new ImpactController.ImpactElement(null, "(화면 로드)", null, List.of())) : s.elements();
            for (var e : els) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("screenCode", s.screenCode());
                m.put("screenName", s.name());
                m.put("menuPath", String.join(" > ", s.menuPath()));
                m.put("element", e.label());
                m.put("crud", e.crud());
                m.put("path", String.join(" → ", e.path()));
                m.put("owner", s.owner());
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public Map<String, String> commonValues(Map<String, String> params) {
        var r = impact.compute(ExcelDataset.required(params, "systemId"), ExcelDataset.required(params, "targetId"),
                params.get("column"));
        Map<String, String> m = new LinkedHashMap<>();
        m.put("target", r.target().name() + (r.target().column() == null ? "" : "." + r.target().column()));
        m.put("screenCount", String.valueOf(r.summary().screens()));
        m.put("elementCount", String.valueOf(r.summary().elements()));
        m.put("srCount", String.valueOf(r.summary().srs()));
        return m;
    }
}
