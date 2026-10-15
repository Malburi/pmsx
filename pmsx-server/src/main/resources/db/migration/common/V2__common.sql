-- PMS-X R0 후반: 공통 기능 (docs/10 명세) + 스키마 v0.2 반영
-- H2(MODE=PostgreSQL) / PostgreSQL 공용 문법만 사용

-- ───────── 사용자 보강 (비밀번호 정책·잠금·LDAP) ─────────
ALTER TABLE CM_USER ADD COLUMN EMAIL VARCHAR(200);
ALTER TABLE CM_USER ADD COLUMN LOCKED BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE CM_USER ADD COLUMN FAIL_COUNT INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE CM_USER ADD COLUMN LAST_LOGIN_AT TIMESTAMP WITH TIME ZONE;
ALTER TABLE CM_USER ADD COLUMN PASSWORD_CHANGED_AT TIMESTAMP WITH TIME ZONE;

CREATE TABLE CM_PASSWORD_HISTORY (
    USER_ID        VARCHAR(26)  NOT NULL REFERENCES CM_USER (USER_ID),
    PASSWORD_HASH  VARCHAR(100) NOT NULL,
    CREATED_AT     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IX_CM_PWHIST_USER ON CM_PASSWORD_HISTORY (USER_ID, CREATED_AT);

-- ───────── 공통코드·용어 ─────────
CREATE TABLE CM_CODE_GROUP (
    GROUP_CODE   VARCHAR(50)   PRIMARY KEY,
    NAME         VARCHAR(100)  NOT NULL,
    DESCRIPTION  VARCHAR(500),
    SYSTEM_YN    BOOLEAN DEFAULT FALSE NOT NULL,
    CREATED_AT   TIMESTAMP WITH TIME ZONE NOT NULL,
    CREATED_BY   VARCHAR(26),
    UPDATED_AT   TIMESTAMP WITH TIME ZONE,
    UPDATED_BY   VARCHAR(26)
);

CREATE TABLE CM_CODE (
    GROUP_CODE  VARCHAR(50)  NOT NULL REFERENCES CM_CODE_GROUP (GROUP_CODE),
    CODE        VARCHAR(50)  NOT NULL,
    NAME        VARCHAR(200) NOT NULL,
    SORT_ORD    INTEGER DEFAULT 0 NOT NULL,
    USE_YN      BOOLEAN DEFAULT TRUE NOT NULL,
    ATTR1       VARCHAR(200),
    ATTR2       VARCHAR(200),
    PRIMARY KEY (GROUP_CODE, CODE)
);

CREATE TABLE CM_TERM (
    TERM_ID     VARCHAR(26)   PRIMARY KEY,
    TERM        VARCHAR(200)  NOT NULL,
    ABBR        VARCHAR(100),
    DEFINITION  VARCHAR(2000) NOT NULL,
    TERM_DOMAIN VARCHAR(100),
    CREATED_AT  TIMESTAMP WITH TIME ZONE NOT NULL,
    CREATED_BY  VARCHAR(26),
    UPDATED_AT  TIMESTAMP WITH TIME ZONE,
    UPDATED_BY  VARCHAR(26)
);
CREATE INDEX IX_CM_TERM_TERM ON CM_TERM (TERM);

-- ───────── 첨부 (대상별 파일, 같은 이름 = 새 버전) ─────────
CREATE TABLE CM_ATTACH (
    ATTACH_ID       VARCHAR(26)  PRIMARY KEY,
    TARGET_TYPE     VARCHAR(50)  NOT NULL,
    TARGET_ID       VARCHAR(100) NOT NULL,
    NAME            VARCHAR(300) NOT NULL,
    LATEST_VERSION  INTEGER      NOT NULL,
    DELETED         BOOLEAN DEFAULT FALSE NOT NULL,
    CREATED_AT      TIMESTAMP WITH TIME ZONE NOT NULL,
    CREATED_BY      VARCHAR(26),
    UPDATED_AT      TIMESTAMP WITH TIME ZONE,
    UPDATED_BY      VARCHAR(26)
);
CREATE INDEX IX_CM_ATTACH_TARGET ON CM_ATTACH (TARGET_TYPE, TARGET_ID);

CREATE TABLE CM_ATTACH_VERSION (
    ATTACH_ID   VARCHAR(26) NOT NULL REFERENCES CM_ATTACH (ATTACH_ID),
    VERSION     INTEGER     NOT NULL,
    FILE_ID     VARCHAR(26) NOT NULL REFERENCES CM_FILE (FILE_ID),
    CREATED_AT  TIMESTAMP WITH TIME ZONE NOT NULL,
    CREATED_BY  VARCHAR(26),
    PRIMARY KEY (ATTACH_ID, VERSION)
);

-- ───────── 엑셀 템플릿 ─────────
CREATE TABLE CM_EXCEL_TEMPLATE (
    TEMPLATE_ID   VARCHAR(26)   PRIMARY KEY,
    NAME          VARCHAR(200)  NOT NULL,
    DATASET_ID    VARCHAR(50)   NOT NULL,
    FILE_ID       VARCHAR(26)   NOT NULL REFERENCES CM_FILE (FILE_ID),
    MAPPING_JSON  VARCHAR(4000) NOT NULL,
    CREATED_AT    TIMESTAMP WITH TIME ZONE NOT NULL,
    CREATED_BY    VARCHAR(26),
    UPDATED_AT    TIMESTAMP WITH TIME ZONE,
    UPDATED_BY    VARCHAR(26)
);

-- ───────── 감사 로그 ─────────
CREATE TABLE CM_AUDIT_LOG (
    LOG_ID      VARCHAR(26)   PRIMARY KEY,
    LOG_AT      TIMESTAMP WITH TIME ZONE NOT NULL,
    USER_ID     VARCHAR(26),
    LOGIN_ID    VARCHAR(50),
    ACTION      VARCHAR(20)   NOT NULL,
    TARGET      VARCHAR(500),
    DETAIL      VARCHAR(2000),
    IP          VARCHAR(64),
    PROJECT_ID  VARCHAR(26)
);
CREATE INDEX IX_CM_AUDIT_AT ON CM_AUDIT_LOG (LOG_AT);
CREATE INDEX IX_CM_AUDIT_USER ON CM_AUDIT_LOG (USER_ID, LOG_AT);

-- ───────── 라이선스 (적용된 키 1건) ─────────
CREATE TABLE CM_LICENSE (
    LICENSE_ID   VARCHAR(10)   PRIMARY KEY,          -- 'CURRENT'
    LICENSE_KEY  VARCHAR(4000) NOT NULL,
    APPLIED_AT   TIMESTAMP WITH TIME ZONE NOT NULL,
    APPLIED_BY   VARCHAR(26)
);

-- ───────── 스키마 v0.2: 캡처 배율 ─────────
ALTER TABLE AS_CAPTURE ADD COLUMN SCALE DOUBLE PRECISION DEFAULT 1 NOT NULL;

-- ───────── 기준 코드 (system=true, 삭제 불가) ─────────
INSERT INTO CM_CODE_GROUP (GROUP_CODE, NAME, DESCRIPTION, SYSTEM_YN, CREATED_AT) VALUES
    ('PROJECT_TYPE',    '프로젝트 유형', 'SI 구축 / SM 운영', TRUE, CURRENT_TIMESTAMP),
    ('ANALYSIS_STATUS', '분석 상태',     'As-Is 화면 분석 상태', TRUE, CURRENT_TIMESTAMP),
    ('ELEMENT_TYPE',    '화면 요소 유형', '수집 모델 elements.type', TRUE, CURRENT_TIMESTAMP),
    ('UI_TECH',         '화면 기술',     '수집 모델 uiTech', TRUE, CURRENT_TIMESTAMP),
    ('DB_TYPE',         'DB 종류',       '수집 모델 dbType', TRUE, CURRENT_TIMESTAMP),
    ('ORG_TYPE',        '소속 구분',     '사용자 소속', TRUE, CURRENT_TIMESTAMP),
    ('GRADE',           '기술 등급',     '투입 인력 등급', TRUE, CURRENT_TIMESTAMP);

INSERT INTO CM_CODE (GROUP_CODE, CODE, NAME, SORT_ORD) VALUES
    ('PROJECT_TYPE', 'SI', 'SI 구축', 1),
    ('PROJECT_TYPE', 'SM', 'SM 운영', 2),
    ('ANALYSIS_STATUS', 'AUTO',    '자동수집', 1),
    ('ANALYSIS_STATUS', 'WORKING', '분석중', 2),
    ('ANALYSIS_STATUS', 'REVIEW',  '검토요청', 3),
    ('ANALYSIS_STATUS', 'DONE',    '확정', 4),
    ('ELEMENT_TYPE', 'button', '버튼', 1),
    ('ELEMENT_TYPE', 'input',  '입력', 2),
    ('ELEMENT_TYPE', 'combo',  '콤보', 3),
    ('ELEMENT_TYPE', 'check',  '체크박스', 4),
    ('ELEMENT_TYPE', 'radio',  '라디오', 5),
    ('ELEMENT_TYPE', 'date',   '날짜', 6),
    ('ELEMENT_TYPE', 'grid',   '그리드', 7),
    ('ELEMENT_TYPE', 'tab',    '탭', 8),
    ('ELEMENT_TYPE', 'tree',   '트리', 9),
    ('ELEMENT_TYPE', 'popup',  '팝업호출', 10),
    ('ELEMENT_TYPE', 'link',   '링크', 11),
    ('ELEMENT_TYPE', 'file',   '파일', 12),
    ('ELEMENT_TYPE', 'other',  '기타', 13),
    ('UI_TECH', 'jsp-jquery',      'JSP + jQuery', 1),
    ('UI_TECH', 'nexacro',         'Nexacro', 2),
    ('UI_TECH', 'miplatform',      'MiPlatform', 3),
    ('UI_TECH', 'xplatform',       'XPlatform', 4),
    ('UI_TECH', 'websquare',       'WebSquare', 5),
    ('UI_TECH', 'vue',             'Vue', 6),
    ('UI_TECH', 'react',           'React', 7),
    ('UI_TECH', 'winforms',        'WinForms', 8),
    ('UI_TECH', 'aspnet-webforms', 'ASP.NET WebForms', 9),
    ('UI_TECH', 'aspnet-mvc',      'ASP.NET MVC', 10),
    ('UI_TECH', 'other',           '기타', 11),
    ('DB_TYPE', 'oracle',     'Oracle', 1),
    ('DB_TYPE', 'tibero',     'Tibero', 2),
    ('DB_TYPE', 'mssql',      'MS SQL Server', 3),
    ('DB_TYPE', 'postgresql', 'PostgreSQL', 4),
    ('DB_TYPE', 'mysql',      'MySQL', 5),
    ('DB_TYPE', 'mariadb',    'MariaDB', 6),
    ('DB_TYPE', 'other',      '기타', 7),
    ('ORG_TYPE', 'INTERNAL', '자사', 1),
    ('ORG_TYPE', 'PARTNER',  '협력사', 2),
    ('ORG_TYPE', 'CUSTOMER', '고객', 3),
    ('GRADE', 'SPECIAL', '특급', 1),
    ('GRADE', 'HIGH',    '고급', 2),
    ('GRADE', 'MIDDLE',  '중급', 3),
    ('GRADE', 'JUNIOR',  '초급', 4);
