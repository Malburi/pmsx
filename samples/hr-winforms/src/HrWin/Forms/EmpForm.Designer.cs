namespace HrWin.Forms
{
    partial class EmpForm
    {
        private System.ComponentModel.IContainer components = null;

        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        private void InitializeComponent()
        {
            this.grpSearch = new System.Windows.Forms.GroupBox();
            this.lblEmpNm = new System.Windows.Forms.Label();
            this.txtEmpNm = new System.Windows.Forms.TextBox();
            this.btnEmpSearch = new System.Windows.Forms.Button();
            this.lblDept = new System.Windows.Forms.Label();
            this.cboDept = new System.Windows.Forms.ComboBox();
            this.btnSearch = new System.Windows.Forms.Button();
            this.btnExcel = new System.Windows.Forms.Button();
            this.dgvEmp = new System.Windows.Forms.DataGridView();
            this.colEmpNo = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.colEmpNm = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.colDeptNm = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.colHireDt = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.btnNew = new System.Windows.Forms.Button();
            this.btnSave = new System.Windows.Forms.Button();
            this.btnDelete = new System.Windows.Forms.Button();
            this.grpSearch.SuspendLayout();
            ((System.ComponentModel.ISupportInitialize)(this.dgvEmp)).BeginInit();
            this.SuspendLayout();
            //
            // grpSearch
            //
            this.grpSearch.Controls.Add(this.lblEmpNm);
            this.grpSearch.Controls.Add(this.txtEmpNm);
            this.grpSearch.Controls.Add(this.btnEmpSearch);
            this.grpSearch.Controls.Add(this.lblDept);
            this.grpSearch.Controls.Add(this.cboDept);
            this.grpSearch.Controls.Add(this.btnSearch);
            this.grpSearch.Location = new System.Drawing.Point(12, 12);
            this.grpSearch.Name = "grpSearch";
            this.grpSearch.Size = new System.Drawing.Size(860, 60);
            this.grpSearch.TabIndex = 0;
            this.grpSearch.TabStop = false;
            this.grpSearch.Text = "검색조건";
            //
            // lblEmpNm
            //
            this.lblEmpNm.AutoSize = true;
            this.lblEmpNm.Location = new System.Drawing.Point(15, 27);
            this.lblEmpNm.Name = "lblEmpNm";
            this.lblEmpNm.Size = new System.Drawing.Size(43, 15);
            this.lblEmpNm.TabIndex = 0;
            this.lblEmpNm.Text = "사원명";
            //
            // txtEmpNm
            //
            this.txtEmpNm.Location = new System.Drawing.Point(64, 23);
            this.txtEmpNm.Name = "txtEmpNm";
            this.txtEmpNm.Size = new System.Drawing.Size(150, 23);
            this.txtEmpNm.TabIndex = 1;
            this.txtEmpNm.KeyDown += new System.Windows.Forms.KeyEventHandler(this.txtEmpNm_KeyDown);
            //
            // btnEmpSearch
            //
            this.btnEmpSearch.Location = new System.Drawing.Point(217, 22);
            this.btnEmpSearch.Name = "btnEmpSearch";
            this.btnEmpSearch.Size = new System.Drawing.Size(30, 25);
            this.btnEmpSearch.TabIndex = 2;
            this.btnEmpSearch.Text = "…";
            this.btnEmpSearch.UseVisualStyleBackColor = true;
            this.btnEmpSearch.Click += new System.EventHandler(this.btnEmpSearch_Click);
            //
            // lblDept
            //
            this.lblDept.AutoSize = true;
            this.lblDept.Location = new System.Drawing.Point(275, 27);
            this.lblDept.Name = "lblDept";
            this.lblDept.Size = new System.Drawing.Size(31, 15);
            this.lblDept.TabIndex = 3;
            this.lblDept.Text = "부서";
            //
            // cboDept
            //
            this.cboDept.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cboDept.Location = new System.Drawing.Point(312, 23);
            this.cboDept.Name = "cboDept";
            this.cboDept.Size = new System.Drawing.Size(150, 23);
            this.cboDept.TabIndex = 4;
            this.cboDept.SelectedIndexChanged += new System.EventHandler(this.cboDept_SelectedIndexChanged);
            //
            // btnSearch
            //
            this.btnSearch.Location = new System.Drawing.Point(770, 20);
            this.btnSearch.Name = "btnSearch";
            this.btnSearch.Size = new System.Drawing.Size(75, 27);
            this.btnSearch.TabIndex = 5;
            this.btnSearch.Text = "조회";
            this.btnSearch.UseVisualStyleBackColor = true;
            this.btnSearch.Click += new System.EventHandler(this.btnSearch_Click);
            //
            // btnExcel
            //
            this.btnExcel.Location = new System.Drawing.Point(797, 80);
            this.btnExcel.Name = "btnExcel";
            this.btnExcel.Size = new System.Drawing.Size(75, 27);
            this.btnExcel.TabIndex = 1;
            this.btnExcel.Text = "엑셀";
            this.btnExcel.UseVisualStyleBackColor = true;
            //
            // dgvEmp
            //
            this.dgvEmp.AllowUserToAddRows = false;
            this.dgvEmp.ColumnHeadersHeightSizeMode = System.Windows.Forms.DataGridViewColumnHeadersHeightSizeMode.AutoSize;
            this.dgvEmp.Columns.AddRange(new System.Windows.Forms.DataGridViewColumn[] {
            this.colEmpNo,
            this.colEmpNm,
            this.colDeptNm,
            this.colHireDt});
            this.dgvEmp.Location = new System.Drawing.Point(12, 113);
            this.dgvEmp.Name = "dgvEmp";
            this.dgvEmp.RowTemplate.Height = 25;
            this.dgvEmp.Size = new System.Drawing.Size(860, 330);
            this.dgvEmp.TabIndex = 2;
            this.dgvEmp.CellDoubleClick += new System.Windows.Forms.DataGridViewCellEventHandler(this.dgvEmp_CellDoubleClick);
            //
            // colEmpNo
            //
            this.colEmpNo.DataPropertyName = "EmpNo";
            this.colEmpNo.HeaderText = "사번";
            this.colEmpNo.Name = "colEmpNo";
            this.colEmpNo.Width = 120;
            //
            // colEmpNm
            //
            this.colEmpNm.DataPropertyName = "EmpNm";
            this.colEmpNm.HeaderText = "사원명";
            this.colEmpNm.Name = "colEmpNm";
            this.colEmpNm.Width = 160;
            //
            // colDeptNm
            //
            this.colDeptNm.DataPropertyName = "DeptNm";
            this.colDeptNm.HeaderText = "부서";
            this.colDeptNm.Name = "colDeptNm";
            this.colDeptNm.Width = 200;
            //
            // colHireDt
            //
            this.colHireDt.DataPropertyName = "HireDt";
            this.colHireDt.HeaderText = "입사일";
            this.colHireDt.Name = "colHireDt";
            this.colHireDt.Width = 140;
            //
            // btnNew
            //
            this.btnNew.Location = new System.Drawing.Point(635, 452);
            this.btnNew.Name = "btnNew";
            this.btnNew.Size = new System.Drawing.Size(75, 27);
            this.btnNew.TabIndex = 3;
            this.btnNew.Text = "신규";
            this.btnNew.UseVisualStyleBackColor = true;
            this.btnNew.Click += new System.EventHandler(this.btnNew_Click);
            //
            // btnSave
            //
            this.btnSave.Location = new System.Drawing.Point(716, 452);
            this.btnSave.Name = "btnSave";
            this.btnSave.Size = new System.Drawing.Size(75, 27);
            this.btnSave.TabIndex = 4;
            this.btnSave.Text = "저장";
            this.btnSave.UseVisualStyleBackColor = true;
            this.btnSave.Click += new System.EventHandler(this.btnSave_Click);
            //
            // btnDelete
            //
            this.btnDelete.Location = new System.Drawing.Point(797, 452);
            this.btnDelete.Name = "btnDelete";
            this.btnDelete.Size = new System.Drawing.Size(75, 27);
            this.btnDelete.TabIndex = 5;
            this.btnDelete.Text = "삭제";
            this.btnDelete.UseVisualStyleBackColor = true;
            this.btnDelete.Click += new System.EventHandler(this.btnDelete_Click);
            //
            // EmpForm
            //
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 15F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(884, 491);
            this.Controls.Add(this.btnDelete);
            this.Controls.Add(this.btnSave);
            this.Controls.Add(this.btnNew);
            this.Controls.Add(this.dgvEmp);
            this.Controls.Add(this.btnExcel);
            this.Controls.Add(this.grpSearch);
            this.Name = "EmpForm";
            this.Text = "사원관리";
            this.Load += new System.EventHandler(this.EmpForm_Load);
            this.grpSearch.ResumeLayout(false);
            this.grpSearch.PerformLayout();
            ((System.ComponentModel.ISupportInitialize)(this.dgvEmp)).EndInit();
            this.ResumeLayout(false);
        }

        #endregion

        private System.Windows.Forms.GroupBox grpSearch;
        private System.Windows.Forms.Label lblEmpNm;
        private System.Windows.Forms.TextBox txtEmpNm;
        private System.Windows.Forms.Button btnEmpSearch;
        private System.Windows.Forms.Label lblDept;
        private System.Windows.Forms.ComboBox cboDept;
        private System.Windows.Forms.Button btnSearch;
        private System.Windows.Forms.Button btnExcel;
        private System.Windows.Forms.DataGridView dgvEmp;
        private System.Windows.Forms.DataGridViewTextBoxColumn colEmpNo;
        private System.Windows.Forms.DataGridViewTextBoxColumn colEmpNm;
        private System.Windows.Forms.DataGridViewTextBoxColumn colDeptNm;
        private System.Windows.Forms.DataGridViewTextBoxColumn colHireDt;
        private System.Windows.Forms.Button btnNew;
        private System.Windows.Forms.Button btnSave;
        private System.Windows.Forms.Button btnDelete;
    }
}
