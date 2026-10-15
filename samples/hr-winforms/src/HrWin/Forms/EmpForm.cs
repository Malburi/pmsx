using HrWin.Data;
using HrWin.Models;
using HrWin.Services;

namespace HrWin.Forms
{
    /// <summary>사원관리 (HW0101).</summary>
    public partial class EmpForm : Form
    {
        private readonly IEmpService _empService = new EmpService(new EmpDao());
        private readonly DeptService _deptService = new DeptService();

        public EmpForm()
        {
            InitializeComponent();
            dgvEmp.AutoGenerateColumns = false;
        }

        private void EmpForm_Load(object sender, EventArgs e)
        {
            LoadDeptCombo();
            SearchEmp();
        }

        private void LoadDeptCombo()
        {
            var depts = _deptService.GetDeptList();
            cboDept.DisplayMember = "DeptNm";
            cboDept.ValueMember = "DeptCd";
            cboDept.DataSource = depts;
            cboDept.SelectedIndex = -1;
        }

        private void btnSearch_Click(object sender, EventArgs e)
        {
            SearchEmp();
        }

        private void txtEmpNm_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Enter)
                SearchEmp();
        }

        private void cboDept_SelectedIndexChanged(object sender, EventArgs e)
        {
            if (cboDept.Focused)
                SearchEmp();
        }

        private void SearchEmp()
        {
            var deptCd = cboDept.SelectedValue as string;
            dgvEmp.DataSource = _empService.GetEmpList(txtEmpNm.Text, deptCd);
        }

        private void btnEmpSearch_Click(object sender, EventArgs e)
        {
            using var popup = new EmpSearchPopup();
            if (popup.ShowDialog(this) == DialogResult.OK)
            {
                txtEmpNm.Text = popup.SelectedEmpNm;
                SearchEmp();
            }
        }

        private void btnNew_Click(object sender, EventArgs e)
        {
            var list = (dgvEmp.DataSource as List<Emp>) ?? new List<Emp>();
            list.Add(new Emp { HireDt = DateTime.Today });
            dgvEmp.DataSource = null;
            dgvEmp.DataSource = list;
        }

        private void btnSave_Click(object sender, EventArgs e)
        {
            if (dgvEmp.CurrentRow?.DataBoundItem is not Emp emp)
            {
                MessageBox.Show("저장할 사원을 선택하세요.", "알림");
                return;
            }
            if (MessageBox.Show("저장하시겠습니까?", "확인", MessageBoxButtons.YesNo) != DialogResult.Yes)
                return;
            _empService.SaveEmp(emp);
            MessageBox.Show("저장되었습니다.", "알림");
            SearchEmp();
        }

        private void btnDelete_Click(object sender, EventArgs e)
        {
            if (dgvEmp.CurrentRow?.DataBoundItem is not Emp emp)
                return;
            if (MessageBox.Show("삭제하시겠습니까?", "확인", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes)
            {
                _empService.DeleteEmp(emp.EmpNo);
                SearchEmp();
            }
        }

        private void dgvEmp_CellDoubleClick(object sender, DataGridViewCellEventArgs e)
        {
            if (e.RowIndex >= 0 && dgvEmp.Rows[e.RowIndex].DataBoundItem is Emp emp)
                txtEmpNm.Text = emp.EmpNm;
        }
    }
}
