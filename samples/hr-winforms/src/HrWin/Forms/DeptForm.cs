using HrWin.Models;
using HrWin.Services;

namespace HrWin.Forms
{
    /// <summary>부서관리 (HW0102) — EntityFramework 스타일 서비스 사용.</summary>
    public partial class DeptForm : Form
    {
        private readonly DeptService _deptService = new DeptService();

        public DeptForm()
        {
            InitializeComponent();
        }

        private void DeptForm_Load(object sender, EventArgs e)
        {
            BindDepts();
        }

        private void btnDeptSearch_Click(object sender, EventArgs e)
        {
            BindDepts();
        }

        private void btnAddDept_Click(object sender, EventArgs e)
        {
            _deptService.AddDept(new Dept { DeptCd = "D" + DateTime.Now.ToString("HHmmss"), DeptNm = "새 부서" });
            BindDepts();
        }

        private void btnRename_Click(object sender, EventArgs e)
        {
            if (tvDept.SelectedNode == null || string.IsNullOrWhiteSpace(txtNewName.Text))
                return;
            _deptService.RenameDept((string)tvDept.SelectedNode.Tag, txtNewName.Text);
            BindDepts();
        }

        private void BindDepts()
        {
            var depts = _deptService.GetDeptList();
            dgvDept.DataSource = null;
            dgvDept.DataSource = depts.ToList();
            tvDept.Nodes.Clear();
            foreach (var d in depts)
                tvDept.Nodes.Add(new TreeNode(d.DeptNm) { Tag = d.DeptCd });
        }
    }
}
