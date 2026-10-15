namespace HrWin.Forms
{
    public partial class MainForm : Form
    {
        public MainForm()
        {
            InitializeComponent();
        }

        private void mnuEmp_Click(object sender, EventArgs e)
        {
            var frm = new EmpForm();
            frm.Show();
        }

        private void mnuDept_Click(object sender, EventArgs e)
        {
            new DeptForm().Show();
        }

        private void mnuAttendList_Click(object sender, EventArgs e)
        {
            OpenForm(new AttendForm());
        }

        private void mnuAttend_Click(object sender, EventArgs e)
        {
            // 근태신청 화면은 아직 없음 (메뉴만 등록된 사례 — 수집기 실패 기록 검증용)
            MessageBox.Show("준비 중입니다.", "알림");
        }

        private void OpenForm(Form frm)
        {
            frm.Show();
        }
    }
}
