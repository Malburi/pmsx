using System.ComponentModel.DataAnnotations.Schema;

namespace HrWin.Models
{
    public class Emp
    {
        public string EmpNo { get; set; }
        public string EmpNm { get; set; }
        public string DeptCd { get; set; }
        public string DeptNm { get; set; }
        public DateTime HireDt { get; set; }
        public string RowStatus { get; set; }
    }

    [Table("TB_DEPT")]
    public class Dept
    {
        public string DeptCd { get; set; }
        public string DeptNm { get; set; }
        public string UpDeptCd { get; set; }
        public int SortOrd { get; set; }
        public string UseYn { get; set; } = "Y";
    }

    public class Attend
    {
        public string EmpNo { get; set; }
        public string EmpNm { get; set; }
        public DateTime WorkDt { get; set; }
        public string InTm { get; set; }
        public string OutTm { get; set; }
        public string AttType { get; set; }
    }
}
