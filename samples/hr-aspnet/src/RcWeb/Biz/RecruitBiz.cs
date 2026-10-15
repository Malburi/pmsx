using System.Collections.Generic;
using System.Data;
using RcWeb.Dac;

namespace RcWeb.Biz
{
    /// <summary>채용공고 업무 계층 (BLL)</summary>
    public class RecruitBiz
    {
        private readonly RecruitDac _dac = new RecruitDac();

        public DataTable GetRecruitList(string title, string status) => _dac.SelectRecruitList(title, status);

        public RecruitInfo GetRecruit(string no) => _dac.SelectRecruit(no);

        public DataTable GetDeptList() => _dac.SelectDeptList();

        public int InsertRecruit(string title, string deptCd, string endDt, string content) =>
            _dac.SaveRecruit(null, title, deptCd, endDt, content);

        public int UpdateRecruit(string no, string title, string deptCd, string endDt, string content) =>
            _dac.SaveRecruit(no, title, deptCd, endDt, content);

        public void CloseRecruit(string no) => _dac.UpdateStatus(no, "CLOSE");

        public int GetOpenCount() => _dac.SelectOpenCount();
    }

    public class RecruitInfo
    {
        public string RecruitNo { get; set; }
        public string Title { get; set; }
    }
}
