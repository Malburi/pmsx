-- 자동 생성: tools/ddl/convert_ddl.py ← common/V2__common.sql (mssql). 직접 고치지 말고 생성기를 고칠 것.
-- PMS-X R0 후반: 공통 기능 (docs/10 명세) + 스키마 v0.2 반영

-- ───────── 사용자 보강 (비밀번호 정책·잠금·LDAP) ─────────
ALTER TABLE CM_USER ADD EMAIL NVARCHAR(200);
ALTER TABLE CM_USER ADD LOCKED ${bit} DEFAULT 0 NOT NULL;
ALTER TABLE CM_USER ADD FAIL_COUNT INT DEFAULT 0 NOT NULL;
ALTER TABLE CM_USER ADD LAST_LOGIN_AT ${tstz};
ALTER TABLE CM_USER ADD PASSWORD_CHANGED_AT ${tstz};

CREATE TABLE CM_PASSWORD_HISTORY (
    USER_ID        NVARCHAR(26)  NOT NULL REFERENCES CM_USER (USER_ID),
    PASSWORD_HASH  NVARCHAR(100) NOT NULL,
    CREATED_AT     ${tstz} NOT NULL
);
CREATE INDEX IX_CM_PWHIST_USER ON CM_PASSWORD_HISTORY (USER_ID, CREATED_AT);

-- ───────── 공통코드·용어 ─────────
CREATE TABLE CM_CODE_GROUP (
    GROUP_CODE   NVARCHAR(50)   PRIMARY KEY,
    NAME         NVARCHAR(100)  NOT NULL,
    DESCRIPTION  NVARCHAR(500),
    SYSTEM_YN    ${bit} DEFAULT 0 NOT NULL,
    CREATED_AT   ${tstz} NOT NULL,
    CREATED_BY   NVARCHAR(26),
    UPDATED_AT   ${tstz},
    UPDATED_BY   NVARCHAR(26)
);

CREATE TABLE CM_CODE (
    GROUP_CODE  NVARCHAR(50)  NOT NULL REFERENCES CM_CODE_GROUP (GROUP_CODE),
    CODE        NVARCHAR(50)  NOT NULL,
    NAME        NVARCHAR(200) NOT NULL,
    SORT_ORD    INT DEFAULT 0 NOT NULL,
    USE_YN      ${bit} DEFAULT 1 NOT NULL,
    ATTR1       NVARCHAR(200),
    ATTR2       NVARCHAR(200),
    PRIMARY KEY (GROUP_CODE, CODE)
);

CREATE TABLE CM_TERM (
    TERM_ID     NVARCHAR(26)   PRIMARY KEY,
    TERM        NVARCHAR(200)  NOT NULL,
    ABBR        NVARCHAR(100),
    DEFINITION  NVARCHAR(2000) NOT NULL,
    TERM_DOMAIN NVARCHAR(100),
    CREATED_AT  ${tstz} NOT NULL,
    CREATED_BY  NVARCHAR(26),
    UPDATED_AT  ${tstz},
    UPDATED_BY  NVARCHAR(26)
);
CREATE INDEX IX_CM_TERM_TERM ON CM_TERM (TERM);

-- ───────── 첨부 (대상별 파일, 같은 이름 = 새 버전) ─────────
CREATE TABLE CM_ATTACH (
    ATTACH_ID       NVARCHAR(26)  PRIMARY KEY,
    TARGET_TYPE     NVARCHAR(50)  NOT NULL,
    TARGET_ID       NVARCHAR(100) NOT NULL,
    NAME            NVARCHAR(300) NOT NULL,
    LATEST_VERSION  INT      NOT NULL,
    DELETED         ${bit} DEFAULT 0 NOT NULL,
    CREATED_AT      ${tstz} NOT NULL,
    CREATED_BY      NVARCHAR(26),
    UPDATED_AT      ${tstz},
    UPDATED_BY      NVARCHAR(26)
);
CREATE INDEX IX_CM_ATTACH_TARGET ON CM_ATTACH (TARGET_TYPE, TARGET_ID);

CREATE TABLE CM_ATTACH_VERSION (
    ATTACH_ID   NVARCHAR(26) NOT NULL REFERENCES CM_ATTACH (ATTACH_ID),
    VERSION     INT     NOT NULL,
    FILE_ID     NVARCHAR(26) NOT NULL REFERENCES CM_FILE (FILE_ID),
    CREATED_AT  ${tstz} NOT NULL,
    CREATED_BY  NVARCHAR(26),
    PRIMARY KEY (ATTACH_ID, VERSION)
);

-- ───────── 엑셀 템플릿 ─────────
CREATE TABLE CM_EXCEL_TEMPLATE (
    TEMPLATE_ID   NVARCHAR(26)   PRIMARY KEY,
    NAME          NVARCHAR(200)  NOT NULL,
    DATASET_ID    NVARCHAR(50)   NOT NULL,
    FILE_ID       NVARCHAR(26)   NOT NULL REFERENCES CM_FILE (FILE_ID),
    MAPPING_JSON  NVARCHAR(4000) NOT NULL,
    CREATED_AT    ${tstz} NOT NULL,
    CREATED_BY    NVARCHAR(26),
    UPDATED_AT    ${tstz},
    UPDATED_BY    NVARCHAR(26)
);

-- ───────── 감사 로그 ─────────
CREATE TABLE CM_AUDIT_LOG (
    LOG_ID      NVARCHAR(26)   PRIMARY KEY,
    LOG_AT      ${tstz} NOT NULL,
    USER_ID     NVARCHAR(26),
    LOGIN_ID    NVARCHAR(50),
    ACTION      NVARCHAR(20)   NOT NULL,
    TARGET      NVARCHAR(500),
    DETAIL      NVARCHAR(2000),
    IP          NVARCHAR(64),
    PROJECT_ID  NVARCHAR(26)
);
CREATE INDEX IX_CM_AUDIT_AT ON CM_AUDIT_LOG (LOG_AT);
CREATE INDEX IX_CM_AUDIT_USER ON CM_AUDIT_LOG (USER_ID, LOG_AT);

-- ───────── 라이선스 (적용된 키 1건) ─────────
CREATE TABLE CM_LICENSE (
    LICENSE_ID   NVARCHAR(10)   PRIMARY KEY,          -- 'CURRENT'
    LICENSE_KEY  NVARCHAR(4000) NOT NULL,
    APPLIED_AT   ${tstz} NOT NULL,
    APPLIED_BY   NVARCHAR(26)
);

-- ───────── 스키마 v0.2: 캡처 배율 ─────────
ALTER TABLE AS_CAPTURE ADD SCALE FLOAT DEFAULT 1 NOT NULL;

-- ───────── 기준 코드 (system=true, 삭제 불가) ─────────
INSERT INTO CM_CODE_GROUP (GROUP_CODE, NAME, DESCRIPTION, SYSTEM_YN, CREATED_AT) VALUES
    ('PROJECT_TYPE',    N'프로젝트 유형', N'SI 구축 / SM 운영', 1, CURRENT_TIMESTAMP),
    ('ANALYSIS_STATUS', N'분석 상태',     N'As-Is 화면 분석 상태', 1, CURRENT_TIMESTAMP),
    ('ELEMENT_TYPE',    N'화면 요소 유형', N'수집 모델 elements.type', 1, CURRENT_TIMESTAMP),
    ('UI_TECH',         N'화면 기술',     N'수집 모델 uiTech', 1, CURRENT_TIMESTAMP),
    ('DB_TYPE',         N'DB 종류',       N'수집 모델 dbType', 1, CURRENT_TIMESTAMP),
    ('ORG_TYPE',        N'소속 구분',     N'사용자 소속', 1, CURRENT_TIMESTAMP),
    ('GRADE',           N'기술 등급',     N'투입 인력 등급', 1, CURRENT_TIMESTAMP);

INSERT INTO CM_CODE (GROUP_CODE, CODE, NAME, SORT_ORD) VALUES
    ('PROJECT_TYPE', 'SI', N'SI 구축', 1),
    ('PROJECT_TYPE', 'SM', N'SM 운영', 2),
    ('ANALYSIS_STATUS', 'AUTO',    N'자동수집', 1),
    ('ANALYSIS_STATUS', 'WORKING', N'분석중', 2),
    ('ANALYSIS_STATUS', 'REVIEW',  N'검토요청', 3),
    ('ANALYSIS_STATUS', 'DONE',    N'확정', 4),
    ('ELEMENT_TYPE', 'button', N'버튼', 1),
    ('ELEMENT_TYPE', 'input',  N'입력', 2),
    ('ELEMENT_TYPE', 'combo',  N'콤보', 3),
    ('ELEMENT_TYPE', 'check',  N'체크박스', 4),
    ('ELEMENT_TYPE', 'radio',  N'라디오', 5),
    ('ELEMENT_TYPE', 'date',   N'날짜', 6),
    ('ELEMENT_TYPE', 'grid',   N'그리드', 7),
    ('ELEMENT_TYPE', 'tab',    N'탭', 8),
    ('ELEMENT_TYPE', 'tree',   N'트리', 9),
    ('ELEMENT_TYPE', 'popup',  N'팝업호출', 10),
    ('ELEMENT_TYPE', 'link',   N'링크', 11),
    ('ELEMENT_TYPE', 'file',   N'파일', 12),
    ('ELEMENT_TYPE', 'other',  N'기타', 13),
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
    ('UI_TECH', 'other',           N'기타', 11),
    ('DB_TYPE', 'oracle',     'Oracle', 1),
    ('DB_TYPE', 'tibero',     'Tibero', 2),
    ('DB_TYPE', 'mssql',      'MS SQL Server', 3),
    ('DB_TYPE', 'postgresql', 'PostgreSQL', 4),
    ('DB_TYPE', 'mysql',      'MySQL', 5),
    ('DB_TYPE', 'mariadb',    'MariaDB', 6),
    ('DB_TYPE', 'other',      N'기타', 7),
    ('ORG_TYPE', 'INTERNAL', N'자사', 1),
    ('ORG_TYPE', 'PARTNER',  N'협력사', 2),
    ('ORG_TYPE', 'CUSTOMER', N'고객', 3),
    ('GRADE', 'SPECIAL', N'특급', 1),
    ('GRADE', 'HIGH',    N'고급', 2),
    ('GRADE', 'MIDDLE',  N'중급', 3),
    ('GRADE', 'JUNIOR',  N'초급', 4);
