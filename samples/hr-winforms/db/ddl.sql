-- 샘플 인사 데스크톱(HW) 테이블 · 프로시저 — MSSQL(T-SQL). 수집기 DDL 파서(db.dialect=tsql) 검증용.
-- TB_EMP.DEPT_CD → TB_DEPT, TB_ATTEND.EMP_NO → TB_EMP 는 FK 를 일부러 두지 않았다 (추정 관계 검증).

CREATE TABLE dbo.TB_DEPT (
    DEPT_CD     VARCHAR(10)   NOT NULL PRIMARY KEY,
    DEPT_NM     NVARCHAR(50)  NOT NULL,
    UP_DEPT_CD  VARCHAR(10)   NULL,
    SORT_ORD    INT           DEFAULT 0,
    USE_YN      CHAR(1)       DEFAULT 'Y'
);

CREATE TABLE dbo.TB_EMP (
    EMP_NO      VARCHAR(10)   NOT NULL PRIMARY KEY,
    EMP_NM      NVARCHAR(50)  NOT NULL,
    DEPT_CD     VARCHAR(10)   NULL,
    HIRE_DT     DATE          NULL,
    POS_CD      VARCHAR(10)   NULL,
    USE_YN      CHAR(1)       DEFAULT 'Y',
    REG_DT      DATETIME      DEFAULT GETDATE(),
    UPD_DT      DATETIME      NULL
)

CREATE TABLE dbo.TB_EMP_HIST (
    HIST_SEQ    INT           IDENTITY(1,1) PRIMARY KEY,
    EMP_NO      VARCHAR(10)   NOT NULL,
    CHG_DT      DATETIME      NOT NULL,
    CHG_TYPE    CHAR(1)       NOT NULL,
    BEFORE_JSON NVARCHAR(MAX) NULL,
    CONSTRAINT FK_EMP_HIST_EMP FOREIGN KEY (EMP_NO) REFERENCES dbo.TB_EMP (EMP_NO)
);

CREATE TABLE dbo.TB_ATTEND (
    EMP_NO      VARCHAR(10)   NOT NULL,
    WORK_DT     DATE          NOT NULL,
    IN_TM       CHAR(5)       NULL,
    OUT_TM      CHAR(5)       NULL,
    ATT_TYPE    NVARCHAR(10)  NULL,
    CLOSE_YN    CHAR(1)       DEFAULT 'N',
    CONSTRAINT PK_TB_ATTEND PRIMARY KEY (EMP_NO, WORK_DT)
);

CREATE TABLE dbo.TB_ATTEND_CLOSE (
    CLOSE_YM    CHAR(6)       NOT NULL PRIMARY KEY,
    CLOSE_DT    DATETIME      NOT NULL,
    CLOSE_USER  VARCHAR(10)   NULL
);

CREATE TABLE dbo.TB_JOB_LOG (
    LOG_SEQ     INT           IDENTITY(1,1) PRIMARY KEY,
    JOB_NM      VARCHAR(30)   NOT NULL,
    MSG         NVARCHAR(200) NULL,
    LOG_DT      DATETIME      NOT NULL
);
GO

EXEC sp_addextendedproperty 'MS_Description', N'부서', 'SCHEMA', 'dbo', 'TABLE', 'TB_DEPT';
EXEC sp_addextendedproperty 'MS_Description', N'사원', 'SCHEMA', 'dbo', 'TABLE', 'TB_EMP';
EXEC sp_addextendedproperty 'MS_Description', N'사원명', 'SCHEMA', 'dbo', 'TABLE', 'TB_EMP', 'COLUMN', 'EMP_NM';
EXEC sp_addextendedproperty 'MS_Description', N'사원 변경 이력', 'SCHEMA', 'dbo', 'TABLE', 'TB_EMP_HIST';
EXEC sp_addextendedproperty 'MS_Description', N'근태', 'SCHEMA', 'dbo', 'TABLE', 'TB_ATTEND';
EXEC sp_addextendedproperty 'MS_Description', N'근태 월마감', 'SCHEMA', 'dbo', 'TABLE', 'TB_ATTEND_CLOSE';
GO

-- 사원 저장: 신규(I)/수정(U) + 변경 이력
CREATE PROCEDURE dbo.USP_EMP_SAVE
    @EMP_NO     VARCHAR(10),
    @EMP_NM     NVARCHAR(50),
    @DEPT_CD    VARCHAR(10),
    @HIRE_DT    DATE,
    @ROW_STATUS CHAR(1)
AS
BEGIN
    SET NOCOUNT ON;
    IF @ROW_STATUS = 'I'
        INSERT INTO dbo.TB_EMP (EMP_NO, EMP_NM, DEPT_CD, HIRE_DT, USE_YN, REG_DT)
        VALUES (@EMP_NO, @EMP_NM, @DEPT_CD, @HIRE_DT, 'Y', GETDATE())
    ELSE
        UPDATE dbo.TB_EMP
           SET EMP_NM = @EMP_NM, DEPT_CD = @DEPT_CD, HIRE_DT = @HIRE_DT, UPD_DT = GETDATE()
         WHERE EMP_NO = @EMP_NO
    INSERT INTO dbo.TB_EMP_HIST (EMP_NO, CHG_DT, CHG_TYPE) VALUES (@EMP_NO, GETDATE(), @ROW_STATUS)
END
GO

-- 근태 월마감 → 로그 프로시저 호출
CREATE PROCEDURE dbo.USP_ATTEND_CLOSE
    @YM CHAR(6)
AS
BEGIN
    SET NOCOUNT ON;
    UPDATE dbo.TB_ATTEND SET CLOSE_YN = 'Y' WHERE CONVERT(CHAR(6), WORK_DT, 112) = @YM
    INSERT INTO dbo.TB_ATTEND_CLOSE (CLOSE_YM, CLOSE_DT) VALUES (@YM, GETDATE())
    EXEC dbo.USP_WRITE_LOG 'ATTEND_CLOSE', @YM
END
GO

CREATE PROCEDURE dbo.USP_WRITE_LOG
    @JOB VARCHAR(30),
    @MSG NVARCHAR(200)
AS
BEGIN
    INSERT INTO dbo.TB_JOB_LOG (JOB_NM, MSG, LOG_DT) VALUES (@JOB, @MSG, GETDATE())
END
GO
