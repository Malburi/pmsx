using HrWin.Models;

namespace HrWin.Data
{
    /// <summary>샘플 목업 데이터 (DB 대신).</summary>
    internal static class MockData
    {
        private static readonly List<Emp> _emps = new()
        {
            new Emp { EmpNo = "20190012", EmpNm = "홍길동", DeptCd = "D10", DeptNm = "인사팀", HireDt = new DateTime(2019, 3, 2) },
            new Emp { EmpNo = "20200105", EmpNm = "김영희", DeptCd = "D20", DeptNm = "재무팀", HireDt = new DateTime(2020, 7, 1) },
            new Emp { EmpNo = "20210033", EmpNm = "이철수", DeptCd = "D30", DeptNm = "정보화팀", HireDt = new DateTime(2021, 1, 4) },
            new Emp { EmpNo = "20220218", EmpNm = "박민수", DeptCd = "D10", DeptNm = "인사팀", HireDt = new DateTime(2022, 9, 15) },
        };

        public static readonly List<Dept> Depts = new()
        {
            new Dept { DeptCd = "D10", DeptNm = "인사팀", SortOrd = 1 },
            new Dept { DeptCd = "D20", DeptNm = "재무팀", SortOrd = 2 },
            new Dept { DeptCd = "D30", DeptNm = "정보화팀", SortOrd = 3 },
        };

        public static List<Emp> Emps(string empNm, string deptCd) =>
            _emps.Where(e => (string.IsNullOrEmpty(empNm) || e.EmpNm.Contains(empNm))
                          && (string.IsNullOrEmpty(deptCd) || e.DeptCd == deptCd)).ToList();

        public static int Save(Emp emp) => 1;

        public static List<Attend> Attends(DateTime from, DateTime to) => _emps.Select((e, i) => new Attend
        {
            EmpNo = e.EmpNo, EmpNm = e.EmpNm, WorkDt = from.AddDays(i), InTm = "09:00", OutTm = "18:00", AttType = "정상",
        }).ToList();
    }
}
