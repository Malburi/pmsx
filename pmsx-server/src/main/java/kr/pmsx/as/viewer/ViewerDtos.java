package kr.pmsx.as.viewer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 08 명세 §4 응답 타입. 필드명은 명세와 1:1. */
public final class ViewerDtos {

    private ViewerDtos() {
    }

    public record MenuScreen(String screenId, String screenCode, String kind, String status) {
    }

    public record MenuNode(String menuId, String name, int order, MenuScreen screen, List<MenuNode> children) {
    }

    public record UserRef(String userId, String name) {
    }

    public record Analysis(String status, UserRef owner, UserRef reviewer, OffsetDateTime updatedAt) {
    }

    /** scale: 이미지 픽셀 / CSS 픽셀 (스키마 v0.2, 0.1 패키지는 1) */
    /** state: 캡처 상태 (예: 초기, 조회후, wireframe = 소스 좌표로 그린 와이어프레임, manual = 수동 업로드) */
    public record Capture(String captureId, int width, int height, OffsetDateTime collectedAt, int runSeq,
                          double scale, String state) {
    }

    public record Box(double x, double y, double w, double h) {
    }

    public record EventRef(String event, String handler) {
    }

    /** manual: 수동 추가·수정됨(재수집 시 보존), hidden: 숨김 처리된 수집 요소 (11 명세) */
    public record ElementSummary(String elementId, int seqNo, String elementCode, String label, String type, Box box,
                                 String binding, String matched, boolean danger, List<EventRef> events,
                                 boolean manual, boolean hidden) {
    }

    public record ScreenDetail(String screenId, String screenCode, String name, String kind, String uiTech,
                               String sourceFile, List<String> menuPath, Analysis analysis, Capture capture,
                               List<ElementSummary> elements) {
    }

    /** edgeId: 수동 보정용 (11 명세 08 변경), confirmed: 확정 엣지는 신뢰도와 관계없이 실선 */
    public record EdgeRef(String edgeId, String kind, String source, double confidence, String condition,
                          String evidence, boolean confirmed, String status) {
    }

    public record WorkflowStep(String nodeId, String lane, String title, String subtitle, int depth, String crud,
                               List<String> columns, boolean hasSource, EdgeRef edge) {
    }

    public record WorkflowEvent(String event, List<WorkflowStep> steps) {
    }

    public record Workflow(String elementId, List<WorkflowEvent> events) {
    }

    public record UsedBy(String screenId, String screenName, String elementId, String elementLabel) {
    }

    public record NodeDetail(String nodeId, String nodeType, String key, String name, String lang, String file,
                             Integer lineStart, Integer lineEnd, String snippet, List<UsedBy> usedBy) {
    }

    public record TableRef(String nodeId, String name) {
    }

    /** screenId·sub(화면명): 시스템 CRUD(by=element)에서 요소가 속한 화면. by=screen 이면 elementId = screenId (11 v0.2) */
    public record CrudRow(String elementId, int seqNo, String label, Map<String, String> cells, String screenId,
                          String sub) {
    }

    public record CrudMatrix(List<TableRef> tables, List<CrudRow> rows) {
    }

    public record SearchHit(String kind, String id, String label, String sub, String screenId, String elementId) {
    }
}
