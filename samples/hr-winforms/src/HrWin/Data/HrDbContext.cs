using HrWin.Data.Stub;
using HrWin.Models;

namespace HrWin.Data
{
    /// <summary>부서 관리 — EntityFramework 스타일 DbContext.</summary>
    public class HrDbContext : DbContext
    {
        public DbSet<Dept> Depts { get; set; } = new DbSet<Dept>();
    }

    public static class DbHelper
    {
        public static SqlConnection CreateConnection()
        {
            var conn = new SqlConnection(AppConfig.ConnectionString);
            conn.Open();
            return conn;
        }
    }
}
