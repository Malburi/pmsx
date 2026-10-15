package kr.pmsx.pj;

/** 프로젝트 등록·수정 후 (같은 트랜잭션 안에서 동기 처리). SM 프로젝트의 기본 SLA 정책 시드 등에 쓴다. */
public record ProjectSavedEvent(String projectId, String type) {
}
