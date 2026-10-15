using System.Data;
using HrWin.Data.Stub;
using HrWin.Models;

namespace HrWin.Data
{
    /// <summary>사원 DAO — ADO.NET(SqlCommand 텍스트·저장 프로시저) + Dapper.</summary>
    public class EmpDao
    {
        private const string SQL_DEPT_CODE = "SELECT DEPT_CD, DEPT_NM FROM dbo.TB_DEPT WHERE USE_YN = 'Y' ORDER BY SORT_ORD";

        public List<Emp> SelectEmpList(string empNm, string deptCd)
        {
            string sql = @"SELECT E.EMP_NO, E.EMP_NM, D.DEPT_NM, E.HIRE_DT
                             FROM dbo.TB_EMP E
                             JOIN dbo.TB_DEPT D ON D.DEPT_CD = E.DEPT_CD
                            WHERE E.USE_YN = 'Y'";
            if (!string.IsNullOrEmpty(empNm))
                sql += " AND E.EMP_NM LIKE '%' + @EmpNm + '%'";
            if (!string.IsNullOrEmpty(deptCd))
                sql += " AND E.DEPT_CD = @DeptCd";
            sql += " ORDER BY E.EMP_NO";

            if (AppConfig.UseMock) return MockData.Emps(empNm, deptCd);

            using var conn = DbHelper.CreateConnection();
            using var cmd = new SqlCommand(sql, conn);
            cmd.Parameters.AddWithValue("@EmpNm", empNm ?? "");
            cmd.Parameters.AddWithValue("@DeptCd", deptCd ?? "");
            cmd.ExecuteScalar();
            return new List<Emp>();
        }

        public DataTable SelectDeptCode()
        {
            if (AppConfig.UseMock) return null;
            using var conn = DbHelper.CreateConnection();
            using var cmd = new SqlCommand();
            cmd.Connection = conn;
            cmd.CommandText = SQL_DEPT_CODE;
            cmd.ExecuteScalar();
            return new DataTable();
        }

        public int SaveEmp(Emp emp)
        {
            if (AppConfig.UseMock) return MockData.Save(emp);
            using var conn = DbHelper.CreateConnection();
            using var cmd = new SqlCommand("dbo.USP_EMP_SAVE", conn);
            cmd.CommandType = CommandType.StoredProcedure;
            cmd.Parameters.AddWithValue("@EMP_NO", emp.EmpNo);
            cmd.Parameters.AddWithValue("@EMP_NM", emp.EmpNm);
            cmd.Parameters.AddWithValue("@DEPT_CD", emp.DeptCd);
            cmd.Parameters.AddWithValue("@HIRE_DT", emp.HireDt);
            cmd.Parameters.AddWithValue("@ROW_STATUS", emp.RowStatus);
            return cmd.ExecuteNonQuery();
        }

        public int DeleteEmp(string empNo)
        {
            if (AppConfig.UseMock) return 1;
            using var conn = DbHelper.CreateConnection();
            return conn.Execute("UPDATE dbo.TB_EMP SET USE_YN = 'N', UPD_DT = GETDATE() WHERE EMP_NO = @EmpNo", new { EmpNo = empNo });
        }
    }
}
