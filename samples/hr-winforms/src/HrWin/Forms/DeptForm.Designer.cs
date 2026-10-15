namespace HrWin.Forms
{
    partial class DeptForm
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
            this.tabDept = new System.Windows.Forms.TabControl();
            this.tabList = new System.Windows.Forms.TabPage();
            this.dgvDept = new System.Windows.Forms.DataGridView();
            this.btnDeptSearch = new System.Windows.Forms.Button();
            this.btnAddDept = new System.Windows.Forms.Button();
            this.tabTree = new System.Windows.Forms.TabPage();
            this.tvDept = new System.Windows.Forms.TreeView();
            this.lblNewName = new System.Windows.Forms.Label();
            this.txtNewName = new System.Windows.Forms.TextBox();
            this.btnRename = new System.Windows.Forms.Button();
            this.tabDept.SuspendLayout();
            this.tabList.SuspendLayout();
            ((System.ComponentModel.ISupportInitialize)(this.dgvDept)).BeginInit();
            this.tabTree.SuspendLayout();
            this.SuspendLayout();
            //
            // tabDept
            //
            this.tabDept.Controls.Add(this.tabList);
            this.tabDept.Controls.Add(this.tabTree);
            this.tabDept.Location = new System.Drawing.Point(12, 12);
            this.tabDept.Name = "tabDept";
            this.tabDept.SelectedIndex = 0;
            this.tabDept.Size = new System.Drawing.Size(760, 420);
            this.tabDept.TabIndex = 0;
            //
            // tabList
            //
            this.tabList.Controls.Add(this.dgvDept);
            this.tabList.Controls.Add(this.btnDeptSearch);
            this.tabList.Controls.Add(this.btnAddDept);
            this.tabList.Location = new System.Drawing.Point(4, 24);
            this.tabList.Name = "tabList";
            this.tabList.Padding = new System.Windows.Forms.Padding(3);
            this.tabList.Size = new System.Drawing.Size(752, 392);
            this.tabList.TabIndex = 0;
            this.tabList.Text = "부서목록";
            this.tabList.UseVisualStyleBackColor = true;
            //
            // dgvDept
            //
            this.dgvDept.AllowUserToAddRows = false;
            this.dgvDept.Location = new System.Drawing.Point(6, 6);
            this.dgvDept.Name = "dgvDept";
            this.dgvDept.Size = new System.Drawing.Size(740, 340);
            this.dgvDept.TabIndex = 0;
            //
            // btnDeptSearch
            //
            this.btnDeptSearch.Location = new System.Drawing.Point(590, 355);
            this.btnDeptSearch.Name = "btnDeptSearch";
            this.btnDeptSearch.Size = new System.Drawing.Size(75, 27);
            this.btnDeptSearch.TabIndex = 1;
            this.btnDeptSearch.Text = "조회";
            this.btnDeptSearch.UseVisualStyleBackColor = true;
            this.btnDeptSearch.Click += new System.EventHandler(this.btnDeptSearch_Click);
            //
            // btnAddDept
            //
            this.btnAddDept.Location = new System.Drawing.Point(671, 355);
            this.btnAddDept.Name = "btnAddDept";
            this.btnAddDept.Size = new System.Drawing.Size(75, 27);
            this.btnAddDept.TabIndex = 2;
            this.btnAddDept.Text = "추가";
            this.btnAddDept.UseVisualStyleBackColor = true;
            this.btnAddDept.Click += new System.EventHandler(this.btnAddDept_Click);
            //
            // tabTree
            //
            this.tabTree.Controls.Add(this.tvDept);
            this.tabTree.Controls.Add(this.lblNewName);
            this.tabTree.Controls.Add(this.txtNewName);
            this.tabTree.Controls.Add(this.btnRename);
            this.tabTree.Location = new System.Drawing.Point(4, 24);
            this.tabTree.Name = "tabTree";
            this.tabTree.Padding = new System.Windows.Forms.Padding(3);
            this.tabTree.Size = new System.Drawing.Size(752, 392);
            this.tabTree.TabIndex = 1;
            this.tabTree.Text = "조직도";
            this.tabTree.UseVisualStyleBackColor = true;
            //
            // tvDept
            //
            this.tvDept.Location = new System.Drawing.Point(6, 6);
            this.tvDept.Name = "tvDept";
            this.tvDept.Size = new System.Drawing.Size(300, 380);
            this.tvDept.TabIndex = 0;
            //
            // lblNewName
            //
            this.lblNewName.AutoSize = true;
            this.lblNewName.Location = new System.Drawing.Point(320, 12);
            this.lblNewName.Name = "lblNewName";
            this.lblNewName.Size = new System.Drawing.Size(55, 15);
            this.lblNewName.TabIndex = 1;
            this.lblNewName.Text = "새 부서명";
            //
            // txtNewName
            //
            this.txtNewName.Location = new System.Drawing.Point(381, 8);
            this.txtNewName.Name = "txtNewName";
            this.txtNewName.Size = new System.Drawing.Size(180, 23);
            this.txtNewName.TabIndex = 2;
            //
            // btnRename
            //
            this.btnRename.Location = new System.Drawing.Point(567, 6);
            this.btnRename.Name = "btnRename";
            this.btnRename.Size = new System.Drawing.Size(90, 27);
            this.btnRename.TabIndex = 3;
            this.btnRename.Text = "이름변경";
            this.btnRename.UseVisualStyleBackColor = true;
            this.btnRename.Click += new System.EventHandler(this.btnRename_Click);
            //
            // DeptForm
            //
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 15F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(784, 444);
            this.Controls.Add(this.tabDept);
            this.Name = "DeptForm";
            this.Text = "부서관리";
            this.Load += new System.EventHandler(this.DeptForm_Load);
            this.tabDept.ResumeLayout(false);
            this.tabList.ResumeLayout(false);
            ((System.ComponentModel.ISupportInitialize)(this.dgvDept)).EndInit();
            this.tabTree.ResumeLayout(false);
            this.tabTree.PerformLayout();
            this.ResumeLayout(false);
        }

        #endregion

        private System.Windows.Forms.TabControl tabDept;
        private System.Windows.Forms.TabPage tabList;
        private System.Windows.Forms.DataGridView dgvDept;
        private System.Windows.Forms.Button btnDeptSearch;
        private System.Windows.Forms.Button btnAddDept;
        private System.Windows.Forms.TabPage tabTree;
        private System.Windows.Forms.TreeView tvDept;
        private System.Windows.Forms.Label lblNewName;
        private System.Windows.Forms.TextBox txtNewName;
        private System.Windows.Forms.Button btnRename;
    }
}
