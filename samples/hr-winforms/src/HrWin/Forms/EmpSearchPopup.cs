using HrWin.Data;
using HrWin.Models;
using HrWin.Services;

namespace HrWin.Forms
{
    /// <summary>사원검색 팝업 (ShowDialog 로 열림).</summary>
    public partial class EmpSearchPopup : Form
    {
        private readonly IEmpService _empService = new EmpService(new EmpDao());

        public string SelectedEmpNm { get; private set; }

        public EmpSearchPopup()
        {
            InitializeComponent();
        }

        private void btnFind_Click(object sender, EventArgs e)
        {
            dgvResult.DataSource = _empService.GetEmpList(txtKeyword.Text, null);
        }

        private void dgvResult_CellDoubleClick(object sender, DataGridViewCellEventArgs e)
        {
            SelectAndClose();
        }

        private void btnSelect_Click(object sender, EventArgs e)
        {
            SelectAndClose();
        }

        private void btnClose_Click(object sender, EventArgs e)
        {
            Close();
        }

        private void SelectAndClose()
        {
            if (dgvResult.CurrentRow?.DataBoundItem is not Emp emp)
                return;
            SelectedEmpNm = emp.EmpNm;
            DialogResult = DialogResult.OK;
            Close();
        }
    }
}
