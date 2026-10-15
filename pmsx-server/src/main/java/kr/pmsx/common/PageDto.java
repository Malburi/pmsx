package kr.pmsx.common;

import java.util.List;

/** 목록 조회 페이지 형식 (docs/10 머리말). page 는 0부터. */
public record PageDto<T>(List<T> content, int page, int size, long totalElements) {

    public static final int MAX_SIZE = 200;

    /** 요청 page/size 를 보정한 값 [page, size] */
    public static int[] normalize(Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? 20 : Math.min(size, MAX_SIZE);
        return new int[] {p, s};
    }
}
