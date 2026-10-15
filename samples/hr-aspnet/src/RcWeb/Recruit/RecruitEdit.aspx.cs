using System;
using System.Web.UI;
using RcWeb.Biz;

namespace RcWeb.Recruit
{
    public partial class RecruitEdit : Page
    {
        private readonly RecruitBiz _biz = new RecruitBiz();

        protected void Page_Load(object sender, EventArgs e)
        {
            if (IsPostBack) return;
            ddlDept.DataSource = _biz.GetDeptList();
            ddlDept.DataBind();
            var no = Request.QueryString["no"];
            if (!string.IsNullOrEmpty(no))
            {
                var r = _biz.GetRecruit(no);
                hdnNo.Value = no;
                txtTitle.Text = r.Title;
            }
        }

        protected void btnSave_Click(object sender, EventArgs e)
        {
            if (string.IsNullOrEmpty(hdnNo.Value))
                _biz.InsertRecruit(txtTitle.Text, ddlDept.SelectedValue, txtEndDt.Text, txtContent.Text);
            else
                _biz.UpdateRecruit(hdnNo.Value, txtTitle.Text, ddlDept.SelectedValue, txtEndDt.Text, txtContent.Text);
            Response.Redirect("~/Recruit/RecruitList.aspx");
        }

        protected void lnkList_Click(object sender, EventArgs e)
        {
            Response.Redirect("~/Recruit/RecruitList.aspx");
        }
    }
}
