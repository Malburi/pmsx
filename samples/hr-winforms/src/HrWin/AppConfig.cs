namespace HrWin
{
    public static class AppConfig
    {
        /// <summary>true 면 DB 대신 메모리 목업 데이터 사용. 샘플은 DB 접속 경로를 실행하지 않는다.</summary>
        public const bool UseMock = true;

        public const string ConnectionString = "Server=hrdb;Database=HR;Integrated Security=true";
    }
}
