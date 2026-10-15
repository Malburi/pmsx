namespace HrWin.Forms
{
    partial class AttendForm
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
            this.pnlSearch = new System.Windows.Forms.Panel();
            this.lblPeriod = new System.Windows.Forms.Label();
            this.dtpFrom = new System.Windows.Forms.DateTimePicker();
            this.dtpTo = new System.Windows.Forms.DateTimePicker();
            this.lblType = new System.Windows.Forms.Label();
            this.cboAttType = new System.Windows.Forms.ComboBox();
            this.chkOnlyLate = new System.Windows.Forms.CheckBox();
            this.btnAttSearch = new System.Windows.Forms.Button();
            this.dgvAttend = new System.Windows.Forms.DataGridView();
            this.btnClose = new System.Windows.Forms.Button();
            this.pnlSearch.SuspendLayout();
            ((System.ComponentModel.ISupportInitialize)(this.dgvAttend)).BeginInit();
            this.SuspendLayout();
            //
            // pnlSearch
            //
            this.pnlSearch.BorderStyle = System.Windows.Forms.BorderStyle.FixedSingle;
            this.pnlSearch.Controls.Add(this.lblPeriod);
            this.pnlSearch.Controls.Add(this.dtpFrom);
            this.pnlSearch.Controls.Add(this.dtpTo);
            this.pnlSearch.Controls.Add(this.lblType);
            this.pnlSearch.Controls.Add(this.cboAttType);
            this.pnlSearch.Controls.Add(this.chkOnlyLate);
            this.pnlSearch.Controls.Add(this.btnAttSearch);
            this.pnlSearch.Location = new System.Drawing.Point(12, 12);
            this.pnlSearch.Name = "pnlSearch";
            this.pnlSearch.Size = new System.Drawing.Size(860, 44);
            this.pnlSearch.TabIndex = 0;
            //
            // lblPeriod
            //
            this.lblPeriod.AutoSize = true;
            this.lblPeriod.Location = new System.Drawing.Point(10, 13);
            this.lblPeriod.Name = "lblPeriod";
            this.lblPeriod.Size = new System.Drawing.Size(31, 15);
            this.lblPeriod.TabIndex = 0;
            this.lblPeriod.Text = "기간";
            //
            // dtpFrom
            //
            this.dtpFrom.Format = System.Windows.Forms.DateTimePickerFormat.Short;
            this.dtpFrom.Location = new System.Drawing.Point(47, 9);
            this.dtpFrom.Name = "dtpFrom";
            this.dtpFrom.Size = new System.Drawing.Size(110, 23);
            this.dtpFrom.TabIndex = 1;
            //
            // dtpTo
            //
            this.dtpTo.Format = System.Windows.Forms.DateTimePickerFormat.Short;
            this.dtpTo.Location = new System.Drawing.Point(163, 9);
            this.dtpTo.Name = "dtpTo";
            this.dtpTo.Size = new System.Drawing.Size(110, 23);
            this.dtpTo.TabIndex = 2;
            //
            // lblType
            //
            this.lblType.AutoSize = true;
            this.lblType.Location = new System.Drawing.Point(290, 13);
            this.lblType.Name = "lblType";
            this.lblType.Size = new System.Drawing.Size(31, 15);
            this.lblType.TabIndex = 3;
            this.lblType.Text = "구분";
            //
            // cboAttType
            //
            this.cboAttType.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cboAttType.Items.AddRange(new object[] {
            "",
            "정상",
            "지각",
            "결근"});
            this.cboAttType.Location = new System.Drawing.Point(327, 9);
            this.cboAttType.Name = "cboAttType";
            this.cboAttType.Size = new System.Drawing.Size(100, 23);
            this.cboAttType.TabIndex = 4;
            //
            // chkOnlyLate
            //
            this.chkOnlyLate.AutoSize = true;
            this.chkOnlyLate.Location = new System.Drawing.Point(445, 12);
            this.chkOnlyLate.Name = "chkOnlyLate";
            this.chkOnlyLate.Size = new System.Drawing.Size(90, 19);
            this.chkOnlyLate.TabIndex = 5;
            this.chkOnlyLate.Text = "지각자만";
            this.chkOnlyLate.UseVisualStyleBackColor = true;
            //
            // btnAttSearch
            //
            this.btnAttSearch.Location = new System.Drawing.Point(770, 7);
            this.btnAttSearch.Name = "btnAttSearch";
            this.btnAttSearch.Size = new System.Drawing.Size(75, 27);
            this.btnAttSearch.TabIndex = 6;
            this.btnAttSearch.Text = "조회";
            this.btnAttSearch.UseVisualStyleBackColor = true;
            this.btnAttSearch.Click += new System.EventHandler(this.btnAttSearch_Click);
            //
            // dgvAttend
            //
            this.dgvAttend.AllowUserToAddRows = false;
            this.dgvAttend.Location = new System.Drawing.Point(12, 64);
            this.dgvAttend.Name = "dgvAttend";
            this.dgvAttend.ReadOnly = true;
            this.dgvAttend.Size = new System.Drawing.Size(860, 360);
            this.dgvAttend.TabIndex = 1;
            //
            // btnClose
            //
            this.btnClose.Location = new System.Drawing.Point(782, 432);
            this.btnClose.Name = "btnClose";
            this.btnClose.Size = new System.Drawing.Size(90, 27);
            this.btnClose.TabIndex = 2;
            this.btnClose.Text = "월마감";
            this.btnClose.UseVisualStyleBackColor = true;
            this.btnClose.Click += new System.EventHandler(this.btnClose_Click);
            //
            // AttendForm
            //
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 15F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(884, 471);
            this.Controls.Add(this.btnClose);
            this.Controls.Add(this.dgvAttend);
            this.Controls.Add(this.pnlSearch);
            this.Name = "AttendForm";
            this.Text = "근태현황";
            this.Load += new System.EventHandler(this.AttendForm_Load);
            this.pnlSearch.ResumeLayout(false);
            this.pnlSearch.PerformLayout();
            ((System.ComponentModel.ISupportInitialize)(this.dgvAttend)).EndInit();
            this.ResumeLayout(false);
        }

        #endregion

        private System.Windows.Forms.Panel pnlSearch;
        private System.Windows.Forms.Label lblPeriod;
        private System.Windows.Forms.DateTimePicker dtpFrom;
        private System.Windows.Forms.DateTimePicker dtpTo;
        private System.Windows.Forms.Label lblType;
        private System.Windows.Forms.ComboBox cboAttType;
        private System.Windows.Forms.CheckBox chkOnlyLate;
        private System.Windows.Forms.Button btnAttSearch;
        private System.Windows.Forms.DataGridView dgvAttend;
        private System.Windows.Forms.Button btnClose;
    }
}
