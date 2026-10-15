using System.Data;
using System.Text;
using HrWin.Data.Stub;
using HrWin.Models;

namespace HrWin.Data
{
    /// <summary>근태 DAO — Dapper(Query/Execute) + StringBuilder 로 조립한 SQL.</summary>
    public class AttendDao
    {
        public List<Attend> SelectAttendList(DateTime from, DateTime to, string attType)
        {
            var sb = new StringBuilder();
            sb.Append("SELECT A.EMP_NO, E.EMP_NM, A.WORK_DT, A.IN_TM, A.OUT_TM, A.ATT_TYPE ");
            sb.Append("  FROM dbo.TB_ATTEND A ");
            sb.Append("  JOIN dbo.TB_EMP E ON E.EMP_NO = A.EMP_NO ");
            sb.Append(" WHERE A.WORK_DT BETWEEN @From AND @To ");
            if (!string.IsNullOrEmpty(attType))
                sb.Append("   AND A.ATT_TYPE = @AttType ");

            if (AppConfig.UseMock) return MockData.Attends(from, to);
            using var conn = DbHelper.CreateConnection();
            return conn.Query<Attend>(sb.ToString(), new { From = from, To = to, AttType = attType }).ToList();
        }

        public int CloseMonth(string ym)
        {
            if (AppConfig.UseMock) return 1;
            using var conn = DbHelper.CreateConnection();
            return conn.Execute("dbo.USP_ATTEND_CLOSE", new { YM = ym }, commandType: CommandType.StoredProcedure);
        }
    }
}
