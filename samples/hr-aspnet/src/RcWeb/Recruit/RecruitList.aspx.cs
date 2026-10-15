using System;
using System.Web.Services;
using System.Web.UI;
using System.Web.UI.WebControls;
using RcWeb.Biz;

namespace RcWeb.Recruit
{
    public partial class RecruitList : Page
    {
        private readonly RecruitBiz _biz = new RecruitBiz();

        protected void Page_Load(object sender, EventArgs e)
        {
            if (!IsPostBack)
            {
                BindGrid();
            }
        }

        protected void btnSearch_Click(object sender, EventArgs e)
        {
            BindGrid();
        }

        protected void ddlStatus_SelectedIndexChanged(object sender, EventArgs e)
        {
            BindGrid();
        }

        private void BindGrid()
        {
            gvRecruit.DataSource = _biz.GetRecruitList(txtTitle.Text, ddlStatus.SelectedValue);
            gvRecruit.DataBind();
        }

        protected void gvRecruit_RowCommand(object sender, GridViewCommandEventArgs e)
        {
            if (e.CommandName == "EditRow")
            {
                var no = gvRecruit.DataKeys[Convert.ToInt32(e.CommandArgument)].Value;
                Response.Redirect("~/Recruit/RecruitEdit.aspx?no=" + no);
            }
        }

        protected void btnNew_Click(object sender, EventArgs e)
        {
            Response.Redirect("~/Recruit/RecruitEdit.aspx");
        }

        protected void btnClose_Click(object sender, EventArgs e)
        {
            foreach (GridViewRow row in gvRecruit.Rows)
            {
                _biz.CloseRecruit(gvRecruit.DataKeys[row.RowIndex].Value.ToString());
            }
            BindGrid();
        }

        /// <summary>페이지 메서드 — recruit.js 가 $.ajax 로 호출</summary>
        [WebMethod]
        public static int GetOpenCount()
        {
            return new RecruitBiz().GetOpenCount();
        }
    }
}
