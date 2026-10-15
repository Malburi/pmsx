using System.Collections.Generic;
using System.Data;
using System.Data.SqlClient;
using System.Linq;
using Dapper;
using RcMvc.Models;

namespace RcMvc.Repositories
{
    /// <summary>지원자 저장소 — Dapper</summary>
    public class ApplicantRepository
    {
        private readonly string _connStr;

        public ApplicantRepository(string connStr)
        {
            _connStr = connStr;
        }

        public IEnumerable<Applicant> Search(ApplicantSearch s)
        {
            const string sql = @"
SELECT A.APPLICANT_ID AS ApplicantId, A.APPLICANT_NM AS Name, R.TITLE AS RecruitTitle, A.STATUS AS Status
  FROM dbo.TB_APPLICANT A
  JOIN dbo.TB_RECRUIT R ON R.RECRUIT_NO = A.RECRUIT_NO
 WHERE A.APPLICANT_NM LIKE @Name
   AND (@Status = '' OR A.STATUS = @Status)";
            using (var conn = new SqlConnection(_connStr))
                return conn.Query<Applicant>(sql, new { Name = "%" + s.Name + "%", Status = s.Status ?? "" }).ToList();
        }

        public Applicant Get(int id)
        {
            using (var conn = new SqlConnection(_connStr))
                return conn.QueryFirstOrDefault<Applicant>(
                    "SELECT APPLICANT_ID AS ApplicantId, APPLICANT_NM AS Name, EMAIL, STATUS FROM dbo.TB_APPLICANT WHERE APPLICANT_ID = @id",
                    new { id });
        }

        public void Insert(Applicant a)
        {
            using (var conn = new SqlConnection(_connStr))
                conn.Execute("dbo.USP_APPLICANT_INSERT", new { a.Name, a.Email, a.RecruitNo }, commandType: CommandType.StoredProcedure);
        }

        public void Update(Applicant a)
        {
            using (var conn = new SqlConnection(_connStr))
                conn.Execute("UPDATE dbo.TB_APPLICANT SET APPLICANT_NM = @Name, EMAIL = @Email WHERE APPLICANT_ID = @ApplicantId", a);
        }

        public void UpdateStatus(int id, string status)
        {
            using (var conn = new SqlConnection(_connStr))
            {
                conn.Execute("UPDATE dbo.TB_APPLICANT SET STATUS = @status WHERE APPLICANT_ID = @id", new { id, status });
                conn.Execute("INSERT INTO dbo.TB_APPLICANT_HIST (APPLICANT_ID, STATUS, CHG_DT) VALUES (@id, @status, GETDATE())", new { id, status });
            }
        }
    }
}
