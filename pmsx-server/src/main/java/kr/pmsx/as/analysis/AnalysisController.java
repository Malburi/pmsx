package kr.pmsx.as.analysis;

import java.util.List;
import java.util.Map;
import kr.pmsx.as.analysis.AnalysisService.AnalysisRow;
import kr.pmsx.as.analysis.AnalysisService.AssignRequest;
import kr.pmsx.as.analysis.AnalysisService.HistoryRow;
import kr.pmsx.as.analysis.AnalysisService.Note;
import kr.pmsx.as.analysis.AnalysisService.NoteRequest;
import kr.pmsx.as.analysis.AnalysisService.Progress;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 메모·분석 이력·일괄 배정·진척 (11 명세 §2). 상태 PATCH 는 ViewerController. */
@RestController
@RequestMapping("/api/v1")
public class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @GetMapping("/notes")
    public List<Note> notes(@AuthenticationPrincipal AuthUser user, @RequestParam String targetType,
            @RequestParam String targetId) {
        return service.notes(user, targetType, targetId);
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public Note create(@AuthenticationPrincipal AuthUser user, @RequestBody NoteRequest req) {
        return service.createNote(user, req);
    }

    @PutMapping("/notes/{noteId}")
    public Note update(@AuthenticationPrincipal AuthUser user, @PathVariable String noteId,
            @RequestBody NoteRequest req) {
        return service.updateNote(user, noteId, req);
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable String noteId) {
        service.deleteNote(user, noteId);
    }

    @GetMapping("/screens/{screenId}/analysis/history")
    public List<HistoryRow> history(@AuthenticationPrincipal AuthUser user, @PathVariable String screenId) {
        return service.history(user, screenId);
    }

    @PostMapping("/systems/{systemId}/analysis/assign")
    public Map<String, Integer> assign(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId,
            @RequestBody AssignRequest req) {
        return service.assign(user, systemId, req);
    }

    @GetMapping("/systems/{systemId}/analysis/progress")
    public Progress progress(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId) {
        return service.progress(user, systemId);
    }

    @GetMapping("/systems/{systemId}/analysis/screens")
    public PageDto<AnalysisRow> screens(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) String menuId, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.screens(user, systemId, status, ownerId, menuId, q, page, size);
    }
}
