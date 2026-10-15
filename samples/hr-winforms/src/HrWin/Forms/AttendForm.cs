using HrWin.Services;

namespace HrWin.Forms
{
    /// <summary>근태현황 (HW0201).</summary>
    public partial class AttendForm : Form
    {
        private readonly AttendService _attendService = new AttendService();

        public AttendForm()
        {
            InitializeComponent();
        }

        private void AttendForm_Load(object sender, EventArgs e)
        {
            dtpFrom.Value = new DateTime(DateTime.Today.Year, DateTime.Today.Month, 1);
            dtpTo.Value = DateTime.Today;
        }

        private void btnAttSearch_Click(object sender, EventArgs e)
        {
            var type = chkOnlyLate.Checked ? "지각" : cboAttType.Text;
            dgvAttend.DataSource = _attendService.GetAttendList(dtpFrom.Value.Date, dtpTo.Value.Date, type);
        }

        private void btnClose_Click(object sender, EventArgs e)
        {
            var ym = dtpFrom.Value.ToString("yyyyMM");
            if (MessageBox.Show($"{ym} 근태를 마감하시겠습니까?", "확인", MessageBoxButtons.OKCancel) == DialogResult.OK)
            {
                _attendService.CloseMonth(ym);
                MessageBox.Show("마감되었습니다.", "알림");
            }
        }
    }
}
