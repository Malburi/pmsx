using HrWin.Forms;

namespace HrWin
{
    internal static class Program
    {
        /// <summary>
        /// 애플리케이션 진입점. 샘플은 항상 목업 데이터로 동작한다(AppConfig.UseMock).
        /// </summary>
        [STAThread]
        static void Main()
        {
            ApplicationConfiguration.Initialize();
            Application.Run(new MainForm());
        }
    }
}
