using System.Configuration;
using System.Data;
using System.Data.SqlClient;
using RcWeb.Biz;

namespace RcWeb.Dac
{
    /// <summary>채용공고 데이터 접근 (ADO.NET)</summary>
    public class RecruitDac
    {
        private static string ConnStr => ConfigurationManager.ConnectionStrings["RcDb"].ConnectionString;

        public DataTable SelectRecruitList(string title, string status)
        {
            var sql = "SELECT R.RECRUIT_NO, R.TITLE, D.DEPT_NM, R.STATUS FROM dbo.TB_RECRUIT R " +
                      "INNER JOIN dbo.TB_DEPT D ON D.DEPT_CD = R.DEPT_CD WHERE R.TITLE LIKE @Title";
            if (!string.IsNullOrEmpty(status))
                sql += " AND R.STATUS = @Status";
            using (var conn = new SqlConnection(ConnStr))
            using (var cmd = new SqlCommand(sql, conn))
            {
                cmd.Parameters.AddWithValue("@Title", "%" + title + "%");
                cmd.Parameters.AddWithValue("@Status", status ?? "");
                var dt = new DataTable();
                new SqlDataAdapter(cmd).Fill(dt);
                return dt;
            }
        }

        public RecruitInfo SelectRecruit(string no)
        {
            using (var conn = new SqlConnection(ConnStr))
            using (var cmd = new SqlCommand("SELECT RECRUIT_NO, TITLE FROM dbo.TB_RECRUIT WHERE RECRUIT_NO = @No", conn))
            {
                cmd.Parameters.AddWithValue("@No", no);
                conn.Open();
                using (var rd = cmd.ExecuteReader())
                    return rd.Read() ? new RecruitInfo { RecruitNo = rd.GetString(0), Title = rd.GetString(1) } : null;
            }
        }

        public DataTable SelectDeptList()
        {
            using (var conn = new SqlConnection(ConnStr))
            using (var cmd = new SqlCommand("SELECT DEPT_CD, DEPT_NM FROM dbo.TB_DEPT ORDER BY DEPT_NM", conn))
            {
                var dt = new DataTable();
                new SqlDataAdapter(cmd).Fill(dt);
                return dt;
            }
        }

        public int SaveRecruit(string no, string title, string deptCd, string endDt, string content)
        {
            using (var conn = new SqlConnection(ConnStr))
            using (var cmd = new SqlCommand("dbo.USP_RECRUIT_SAVE", conn) { CommandType = CommandType.StoredProcedure })
            {
                cmd.Parameters.AddWithValue("@RECRUIT_NO", (object)no ?? System.DBNull.Value);
                cmd.Parameters.AddWithValue("@TITLE", title);
                cmd.Parameters.AddWithValue("@DEPT_CD", deptCd);
                cmd.Parameters.AddWithValue("@END_DT", endDt);
                cmd.Parameters.AddWithValue("@CONTENT", content);
                conn.Open();
                return cmd.ExecuteNonQuery();
            }
        }

        public void UpdateStatus(string no, string status)
        {
            using (var conn = new SqlConnection(ConnStr))
            {
                var cmd = conn.CreateCommand();
                cmd.CommandText = "UPDATE dbo.TB_RECRUIT SET STATUS = @Status, UPD_DT = GETDATE() WHERE RECRUIT_NO = @No";
                cmd.Parameters.AddWithValue("@Status", status);
                cmd.Parameters.AddWithValue("@No", no);
                conn.Open();
                cmd.ExecuteNonQuery();
            }
        }

        public int SelectOpenCount()
        {
            using (var conn = new SqlConnection(ConnStr))
            using (var cmd = new SqlCommand(string.Format("SELECT COUNT(*) FROM dbo.TB_RECRUIT WHERE STATUS = '{0}'", "OPEN"), conn))
            {
                conn.Open();
                return (int)cmd.ExecuteScalar();
            }
        }
    }
}
