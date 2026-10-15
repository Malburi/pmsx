package kr.pmsx.cm.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.common.storage.FileStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 첨부파일 (FR-CM-005, docs/10 §2). 같은 대상(targetType, targetId)에 같은 파일명을 올리면 새 버전.
 * 대상 유형별 권한은 {@link AttachmentTargetPolicy} 빈이 정한다. 등록되지 않은 대상 유형은 거부.
 */
@Service
public class AttachmentService {

    public record Uploader(String userId, String name) {
    }

    public record VersionDto(String fileId, int version, OffsetDateTime uploadedAt) {
    }

    public record FileMeta(String fileId, String attachId, String name, String mime, long size, int version,
                           OffsetDateTime uploadedAt, Uploader uploadedBy, List<VersionDto> versions) {
    }

    private final JdbcTemplate jdbc;
    private final FileStorageService files;
    private final PmsxProperties props;
    private final AuditService audit;
    private final java.util.Map<String, AttachmentTargetPolicy> policies = new java.util.HashMap<>();

    public AttachmentService(JdbcTemplate jdbc, FileStorageService files, PmsxProperties props, AuditService audit,
            List<AttachmentTargetPolicy> policyList) {
        this.jdbc = jdbc;
        this.files = files;
        this.props = props;
        this.audit = audit;
        policyList.forEach(pl -> policies.put(pl.targetType(), pl));
    }

    private AttachmentTargetPolicy policy(String targetType) {
        AttachmentTargetPolicy pl = policies.get(targetType);
        if (pl == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "첨부할 수 없는 대상 유형입니다: " + targetType,
                    java.util.Map.of("targetType", "등록되지 않은 첨부 대상 유형입니다."));
        }
        return pl;
    }

    @Transactional
    public FileMeta upload(AuthUser user, MultipartFile file, String targetType, String targetId) {
        if (targetType == null || targetType.isBlank() || targetId == null || targetId.isBlank()) {
            throw ApiException.badRequest("VALIDATION", "첨부 대상(targetType, targetId)이 필요합니다.");
        }
        AttachmentTargetPolicy pl = policy(targetType);
        pl.check(user, targetId, true);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("VALIDATION", "업로드된 파일이 비어 있습니다.");
        }
        String name = sanitize(file.getOriginalFilename());
        checkRules(name, file.getSize());
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String fileId = files.save(content, name, mime, user.userId());

        String attachId = jdbc.query("SELECT ATTACH_ID FROM CM_ATTACH WHERE TARGET_TYPE = ? AND TARGET_ID = ?"
                        + " AND NAME = ? AND DELETED = FALSE", (rs, i) -> rs.getString(1), targetType, targetId, name)
                .stream().findFirst().orElse(null);
        int version;
        if (attachId == null) {
            attachId = Ulid.next();
            version = 1;
            jdbc.update("INSERT INTO CM_ATTACH (ATTACH_ID, TARGET_TYPE, TARGET_ID, NAME, LATEST_VERSION, CREATED_AT,"
                    + " CREATED_BY) VALUES (?, ?, ?, ?, 1, ?, ?)", attachId, targetType, targetId, name, Times.now(),
                    user.userId());
        } else {
            version = jdbc.queryForObject("SELECT LATEST_VERSION FROM CM_ATTACH WHERE ATTACH_ID = ?", Integer.class,
                    attachId) + 1;
            jdbc.update("UPDATE CM_ATTACH SET LATEST_VERSION = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE ATTACH_ID = ?",
                    version, Times.now(), user.userId(), attachId);
        }
        jdbc.update("INSERT INTO CM_ATTACH_VERSION (ATTACH_ID, VERSION, FILE_ID, CREATED_AT, CREATED_BY)"
                + " VALUES (?, ?, ?, ?, ?)", attachId, version, fileId, Times.now(), user.userId());
        audit.log(Action.UPLOAD, "attach:" + targetType + "/" + targetId, name + " v" + version,
                pl.projectId(targetId));
        return meta(attachId);
    }

    public List<FileMeta> list(AuthUser user, String targetType, String targetId) {
        policy(targetType).check(user, targetId, false);
        return jdbc.queryForList("SELECT ATTACH_ID FROM CM_ATTACH WHERE TARGET_TYPE = ? AND TARGET_ID = ?"
                + " AND DELETED = FALSE ORDER BY CREATED_AT", String.class, targetType, targetId).stream()
                .map(this::meta).toList();
    }

    @Transactional
    public void delete(AuthUser user, String attachId) {
        var row = jdbc.query("SELECT CREATED_BY, TARGET_TYPE, TARGET_ID, NAME FROM CM_ATTACH WHERE ATTACH_ID = ?"
                        + " AND DELETED = FALSE", (rs, i) -> new String[] {rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)}, attachId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("첨부"));
        AttachmentTargetPolicy pl = policy(row[1]);
        pl.check(user, row[2], true);
        if (!user.isSysAdmin() && !user.userId().equals(row[0])) {
            throw ApiException.forbidden();
        }
        jdbc.update("UPDATE CM_ATTACH SET DELETED = TRUE, UPDATED_AT = ?, UPDATED_BY = ? WHERE ATTACH_ID = ?",
                Times.now(), user.userId(), attachId);
        audit.log(Action.DELETE, "attach:" + row[1] + "/" + row[2], row[3]);
    }

    /** 첨부(또는 엑셀 템플릿)로 등록된 파일만 /files 로 내려준다. 캡처·패키지는 각 기능 API 로만. */
    public FileStorageService.StoredFile download(AuthUser user, String fileId, boolean preview) {
        var target = jdbc.query("SELECT A.TARGET_TYPE, A.TARGET_ID FROM CM_ATTACH_VERSION V JOIN CM_ATTACH A"
                + " ON A.ATTACH_ID = V.ATTACH_ID WHERE V.FILE_ID = ? AND A.DELETED = FALSE",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, fileId);
        Integer t = jdbc.queryForObject("SELECT COUNT(*) FROM CM_EXCEL_TEMPLATE WHERE FILE_ID = ?", Integer.class,
                fileId);
        if (target.isEmpty() && (t == null || t == 0)) {
            throw ApiException.notFound("파일");
        }
        if (!target.isEmpty()) {
            policy(target.get(0)[0]).check(user, target.get(0)[1], false);
        }
        FileStorageService.StoredFile f = files.load(fileId);
        if (preview) {
            String m = f.mime().toLowerCase(Locale.ROOT);
            if (!(m.startsWith("image/") || m.equals("application/pdf"))) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PREVIEW_NOT_SUPPORTED",
                        "미리보기는 이미지와 PDF만 지원합니다.");
            }
        } else {
            audit.log(Action.DOWNLOAD, "file:" + fileId, files.info(fileId).originalName());
        }
        return f;
    }

    public FileStorageService.FileInfo info(String fileId) {
        return files.info(fileId);
    }

    /** 크기·확장자 규칙 (엑셀 템플릿 업로드에도 사용) */
    public void checkRules(String name, long size) {
        long max = props.fileMaxSize().toBytes();
        if (size > max) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "파일 크기는 " + props.fileMaxSize().toMegabytes() + "MB 이하여야 합니다.");
        }
        List<String> allowed = props.fileAllowedExt();
        String ext = FileStorageService.extensionOf(name);
        if (!allowed.isEmpty() && (ext.isEmpty() || !allowed.contains(ext.substring(1)))) {
            throw ApiException.badRequest("FILE_TYPE_NOT_ALLOWED", "허용되지 않는 파일 형식입니다: " + name);
        }
    }

    private FileMeta meta(String attachId) {
        var a = jdbc.query("SELECT NAME, LATEST_VERSION FROM CM_ATTACH WHERE ATTACH_ID = ?",
                (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2)}, attachId).get(0);
        List<VersionDto> versions = jdbc.query("SELECT FILE_ID, VERSION, CREATED_AT FROM CM_ATTACH_VERSION"
                        + " WHERE ATTACH_ID = ? ORDER BY VERSION DESC",
                (rs, i) -> new VersionDto(rs.getString(1), rs.getInt(2), Times.read(rs, "CREATED_AT")), attachId);
        VersionDto latest = versions.get(0);
        var latestRow = jdbc.query("""
                SELECT F.MIME, F.SIZE_BYTES, V.CREATED_BY, U.NAME
                  FROM CM_ATTACH_VERSION V JOIN CM_FILE F ON F.FILE_ID = V.FILE_ID
                  LEFT JOIN CM_USER U ON U.USER_ID = V.CREATED_BY
                 WHERE V.ATTACH_ID = ? AND V.VERSION = ?""",
                (rs, i) -> new Object[] {rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)},
                attachId, latest.version()).get(0);
        return new FileMeta(latest.fileId(), attachId, (String) a[0], (String) latestRow[0], (Long) latestRow[1],
                latest.version(), latest.uploadedAt(), new Uploader((String) latestRow[2], (String) latestRow[3]),
                versions);
    }

    static String sanitize(String original) {
        String n = original == null ? "file" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\x00-\\x1f]", "").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            n = "file";
        }
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }
}
