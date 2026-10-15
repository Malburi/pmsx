namespace HrWin.Forms
{
    partial class MainForm
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
            this.menuStrip1 = new System.Windows.Forms.MenuStrip();
            this.mnuHr = new System.Windows.Forms.ToolStripMenuItem();
            this.mnuEmp = new System.Windows.Forms.ToolStripMenuItem();
            this.mnuDept = new System.Windows.Forms.ToolStripMenuItem();
            this.mnuAttend = new System.Windows.Forms.ToolStripMenuItem();
            this.mnuWork = new System.Windows.Forms.ToolStripMenuItem();
            this.mnuAttendList = new System.Windows.Forms.ToolStripMenuItem();
            this.lblWelcome = new System.Windows.Forms.Label();
            this.menuStrip1.SuspendLayout();
            this.SuspendLayout();
            //
            // menuStrip1
            //
            this.menuStrip1.Items.AddRange(new System.Windows.Forms.ToolStripItem[] {
            this.mnuHr,
            this.mnuWork});
            this.menuStrip1.Location = new System.Drawing.Point(0, 0);
            this.menuStrip1.Name = "menuStrip1";
            this.menuStrip1.Size = new System.Drawing.Size(800, 24);
            this.menuStrip1.TabIndex = 0;
            //
            // mnuHr
            //
            this.mnuHr.DropDownItems.AddRange(new System.Windows.Forms.ToolStripItem[] {
            this.mnuEmp,
            this.mnuDept});
            this.mnuHr.Name = "mnuHr";
            this.mnuHr.Text = "인사관리";
            //
            // mnuEmp
            //
            this.mnuEmp.Name = "mnuEmp";
            this.mnuEmp.Text = "사원관리";
            this.mnuEmp.Click += new System.EventHandler(this.mnuEmp_Click);
            //
            // mnuDept
            //
            this.mnuDept.Name = "mnuDept";
            this.mnuDept.Text = "부서관리";
            this.mnuDept.Click += new System.EventHandler(this.mnuDept_Click);
            //
            // mnuWork
            //
            this.mnuWork.DropDownItems.AddRange(new System.Windows.Forms.ToolStripItem[] {
            this.mnuAttendList,
            this.mnuAttend});
            this.mnuWork.Name = "mnuWork";
            this.mnuWork.Text = "근태관리";
            //
            // mnuAttendList
            //
            this.mnuAttendList.Name = "mnuAttendList";
            this.mnuAttendList.Text = "근태현황";
            this.mnuAttendList.Click += new System.EventHandler(this.mnuAttendList_Click);
            //
            // mnuAttend
            //
            this.mnuAttend.Name = "mnuAttend";
            this.mnuAttend.Text = "근태신청";
            this.mnuAttend.Click += new System.EventHandler(this.mnuAttend_Click);
            //
            // lblWelcome
            //
            this.lblWelcome.AutoSize = true;
            this.lblWelcome.Location = new System.Drawing.Point(24, 48);
            this.lblWelcome.Name = "lblWelcome";
            this.lblWelcome.Size = new System.Drawing.Size(300, 15);
            this.lblWelcome.TabIndex = 1;
            this.lblWelcome.Text = "상단 메뉴에서 업무 화면을 여세요.";
            //
            // MainForm
            //
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 15F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(800, 450);
            this.Controls.Add(this.lblWelcome);
            this.Controls.Add(this.menuStrip1);
            this.MainMenuStrip = this.menuStrip1;
            this.Name = "MainForm";
            this.Text = "인사관리 시스템";
            this.menuStrip1.ResumeLayout(false);
            this.menuStrip1.PerformLayout();
            this.ResumeLayout(false);
            this.PerformLayout();
        }

        #endregion

        private System.Windows.Forms.MenuStrip menuStrip1;
        private System.Windows.Forms.ToolStripMenuItem mnuHr;
        private System.Windows.Forms.ToolStripMenuItem mnuEmp;
        private System.Windows.Forms.ToolStripMenuItem mnuDept;
        private System.Windows.Forms.ToolStripMenuItem mnuWork;
        private System.Windows.Forms.ToolStripMenuItem mnuAttendList;
        private System.Windows.Forms.ToolStripMenuItem mnuAttend;
        private System.Windows.Forms.Label lblWelcome;
    }
}
