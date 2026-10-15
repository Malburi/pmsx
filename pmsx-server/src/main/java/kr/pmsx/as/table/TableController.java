package kr.pmsx.as.table;

import java.util.List;
import kr.pmsx.as.table.TableService.Erd;
import kr.pmsx.as.table.TableService.Relation;
import kr.pmsx.as.table.TableService.RelationCreate;
import kr.pmsx.as.table.TableService.RelationPatch;
import kr.pmsx.as.table.TableService.SubjectArea;
import kr.pmsx.as.table.TableService.TableDetail;
import kr.pmsx.as.table.TableService.TableSummary;
import kr.pmsx.as.table.TableService.TableUpdate;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 테이블 정의서·ERD (11 명세 §3). */
@RestController
@RequestMapping("/api/v1")
public class TableController {

    private final TableService tables;

    public TableController(TableService tables) {
        this.tables = tables;
    }

    @GetMapping("/systems/{systemId}/tables")
    public PageDto<TableSummary> list(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId,
            @RequestParam(required = false) String q, @RequestParam(required = false) String subjectArea,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return tables.tables(user, systemId, q, subjectArea, page, size);
    }

    @GetMapping("/tables/{tableId}")
    public TableDetail detail(@AuthenticationPrincipal AuthUser user, @PathVariable String tableId) {
        return tables.table(user, tableId);
    }

    @PutMapping("/tables/{tableId}")
    public TableSummary update(@AuthenticationPrincipal AuthUser user, @PathVariable String tableId,
            @RequestBody TableUpdate req) {
        return tables.update(user, tableId, req);
    }

    @GetMapping("/systems/{systemId}/subject-areas")
    public List<SubjectArea> subjectAreas(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId) {
        return tables.subjectAreas(user, systemId);
    }

    @GetMapping("/systems/{systemId}/erd")
    public Erd erd(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId,
            @RequestParam(required = false) String subjectArea, @RequestParam(required = false) String tableIds,
            @RequestParam(required = false, defaultValue = "true") boolean includeInferred) {
        return tables.erd(user, systemId, subjectArea, tableIds, includeInferred);
    }

    @PostMapping("/relations")
    @ResponseStatus(HttpStatus.CREATED)
    public Relation createRelation(@AuthenticationPrincipal AuthUser user, @RequestBody RelationCreate req) {
        return tables.createRelation(user, req);
    }

    @PatchMapping("/relations/{relationId}")
    public Relation patchRelation(@AuthenticationPrincipal AuthUser user, @PathVariable String relationId,
            @RequestBody RelationPatch req) {
        return tables.patchRelation(user, relationId, req);
    }
}
