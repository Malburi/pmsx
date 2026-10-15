package kr.pmsx.as.ingest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import kr.pmsx.common.ApiException;

/**
 * 결과 패키지(zip)를 메모리로 읽는다. 디스크에 풀지 않으므로 zip-slip 위험이 없고,
 * 항목 수·압축 해제 크기를 제한해 zip bomb 을 막는다.
 * 루트에 model.json 이 없고 최상위 폴더 하나 안에 있으면 그 폴더를 루트로 본다.
 */
public final class PackageReader {

    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;   // 압축 해제 합계 1GB
    static final int MAX_ENTRIES = 50_000;

    private PackageReader() {
    }

    public static Map<String, byte[]> read(byte[] zip) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                if (files.size() >= MAX_ENTRIES) {
                    throw invalid("패키지 항목 수가 너무 많습니다.");
                }
                String name = normalize(e.getName());
                byte[] data = readLimited(in, MAX_TOTAL_BYTES - total);
                total += data.length;
                files.put(name, data);
            }
        } catch (ZipException ex) {
            throw invalid("zip 파일을 읽을 수 없습니다: " + ex.getMessage());
        } catch (IOException ex) {
            throw invalid("패키지를 읽는 중 오류가 발생했습니다.");
        }
        if (files.isEmpty()) {
            throw invalid("zip 파일이 비어 있거나 올바르지 않습니다.");
        }
        return stripSingleRoot(files);
    }

    private static Map<String, byte[]> stripSingleRoot(Map<String, byte[]> files) {
        if (files.containsKey("model.json")) {
            return files;
        }
        String prefix = null;
        for (String name : files.keySet()) {
            if (name.endsWith("/model.json") && name.indexOf('/') == name.length() - "/model.json".length()) {
                prefix = name.substring(0, name.length() - "model.json".length());
                break;
            }
        }
        if (prefix == null) {
            throw invalid("패키지에 model.json 이 없습니다.");
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (var en : files.entrySet()) {
            if (en.getKey().startsWith(prefix)) {
                out.put(en.getKey().substring(prefix.length()), en.getValue());
            }
        }
        return out;
    }

    static String normalize(String raw) {
        String n = raw.replace('\\', '/');
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        if (n.startsWith("/") || n.contains("../") || n.equals("..") || n.contains(":")) {
            throw invalid("허용되지 않는 경로가 포함되어 있습니다: " + raw);
        }
        return n;
    }

    private static byte[] readLimited(InputStream in, long remaining) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        long count = 0;
        while ((r = in.read(buf)) > 0) {
            count += r;
            if (count > remaining) {
                throw invalid("패키지 압축 해제 크기가 제한(1GB)을 넘었습니다.");
            }
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("PACKAGE_INVALID", message);
    }
}
