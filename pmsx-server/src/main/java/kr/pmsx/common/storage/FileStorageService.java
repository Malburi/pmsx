package kr.pmsx.common.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 첨부·캡처·리포트 파일을 로컬 디렉터리에 저장하고 CM_FILE 에 메타를 남긴다. */
@Service
public class FileStorageService {

    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyy/MM");

    private final Path baseDir;
    private final JdbcTemplate jdbc;

    public FileStorageService(PmsxProperties props, JdbcTemplate jdbc) {
        this.baseDir = Path.of(props.storage().dir()).toAbsolutePath().normalize();
        this.jdbc = jdbc;
    }

    public record StoredFile(String fileId, String mime, byte[] content) {
    }

    public record FileInfo(String fileId, String originalName, String mime, long size, String createdBy) {
    }

    public String save(byte[] content, String originalName, String mime, String userId) {
        String fileId = Ulid.next();
        String ext = extension(originalName);
        String relative = Times.now().format(DIR_FMT) + "/" + fileId + ext;
        Path target = baseDir.resolve(relative).normalize();
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 저장 실패: " + target, e);
        }
        jdbc.update("INSERT INTO CM_FILE (FILE_ID, PATH, ORIGINAL_NAME, MIME, SIZE_BYTES, SHA256, CREATED_AT, CREATED_BY)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                fileId, relative, originalName, mime, (long) content.length, sha256(content), Times.now(), userId);
        return fileId;
    }

    public StoredFile load(String fileId) {
        var rows = jdbc.query("SELECT PATH, MIME FROM CM_FILE WHERE FILE_ID = ?",
                (rs, i) -> new String[] {rs.getString("PATH"), rs.getString("MIME")}, fileId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("파일");
        }
        Path p = baseDir.resolve(rows.get(0)[0]).normalize();
        if (!p.startsWith(baseDir) || !Files.exists(p)) {
            throw ApiException.notFound("파일");
        }
        try {
            return new StoredFile(fileId, rows.get(0)[1], Files.readAllBytes(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public FileInfo info(String fileId) {
        return jdbc.query("SELECT FILE_ID, ORIGINAL_NAME, MIME, SIZE_BYTES, CREATED_BY FROM CM_FILE WHERE FILE_ID = ?",
                (rs, i) -> new FileInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5)), fileId).stream().findFirst().orElseThrow(() -> ApiException.notFound("파일"));
    }

    /**
     * 파일과 메타를 지운다. 적재 실패 시 고아 파일 정리용 — 이미 다른 곳에서 참조 중이면 DB 삭제가 실패하므로
     * 그 경우 실제 파일도 남긴다.
     */
    public void delete(String fileId) {
        var rows = jdbc.query("SELECT PATH FROM CM_FILE WHERE FILE_ID = ?", (rs, i) -> rs.getString(1), fileId);
        if (rows.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM CM_FILE WHERE FILE_ID = ?", fileId);
        Path p = baseDir.resolve(rows.get(0)).normalize();
        if (p.startsWith(baseDir)) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                throw new UncheckedIOException("파일 삭제 실패: " + p, e);
            }
        }
    }

    public static String extensionOf(String name) {
        return extension(name);
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,8}") ? ext : "";
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
