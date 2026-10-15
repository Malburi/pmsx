using System.Data;

// 샘플 빌드용 스텁 — 실제 프로젝트의 System.Data.SqlClient / Dapper / EntityFramework 와 같은 모양의 API.
// NuGet 패키지 없이 빌드되도록 최소한만 구현했고, DB 에 접속하지 않는다(Open 하면 예외).
namespace HrWin.Data.Stub
{
    public sealed class SqlConnection : IDisposable
    {
        public SqlConnection(string connectionString) { ConnectionString = connectionString; }
        public string ConnectionString { get; }
        public void Open() => throw new InvalidOperationException("샘플은 DB 에 접속하지 않습니다 (AppConfig.UseMock).");
        public void Dispose() { }
    }

    public sealed class SqlParameterCollection
    {
        private readonly Dictionary<string, object> _values = new();
        public void AddWithValue(string name, object value) => _values[name] = value;
    }

    public sealed class SqlCommand : IDisposable
    {
        public SqlCommand() { }
        public SqlCommand(string commandText, SqlConnection connection) { CommandText = commandText; Connection = connection; }
        public string CommandText { get; set; }
        public CommandType CommandType { get; set; } = CommandType.Text;
        public SqlConnection Connection { get; set; }
        public SqlParameterCollection Parameters { get; } = new();
        public int ExecuteNonQuery() { Connection?.Open(); return 0; }
        public object ExecuteScalar() { Connection?.Open(); return null; }
        public void Dispose() { }
    }

    /// <summary>Dapper 확장 메서드 모양.</summary>
    public static class SqlMapper
    {
        public static IEnumerable<T> Query<T>(this SqlConnection cnn, string sql, object param = null, CommandType? commandType = null)
        {
            cnn.Open();
            return Array.Empty<T>();
        }

        public static int Execute(this SqlConnection cnn, string sql, object param = null, CommandType? commandType = null)
        {
            cnn.Open();
            return 0;
        }
    }

    /// <summary>EntityFramework DbContext / DbSet 모양.</summary>
    public abstract class DbContext : IDisposable
    {
        public virtual int SaveChanges() => 0;
        public void Dispose() { }
    }

    public class DbSet<T> : List<T> where T : class
    {
        public T Find(params object[] keys) => this.FirstOrDefault();
    }
}
