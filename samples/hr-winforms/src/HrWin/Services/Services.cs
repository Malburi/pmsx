using HrWin.Data;
using HrWin.Models;

namespace HrWin.Services
{
    public interface IEmpService
    {
        List<Emp> GetEmpList(string empNm, string deptCd);
        int SaveEmp(Emp emp);
        int DeleteEmp(string empNo);
    }

    public class EmpService : IEmpService
    {
        private readonly EmpDao _empDao;

        public EmpService(EmpDao empDao)
        {
            _empDao = empDao;
        }

        public List<Emp> GetEmpList(string empNm, string deptCd) => _empDao.SelectEmpList(empNm, deptCd);

        public int SaveEmp(Emp emp)
        {
            if (string.IsNullOrWhiteSpace(emp.EmpNm))
                throw new ArgumentException("사원명은 필수입니다.");
            emp.RowStatus = string.IsNullOrEmpty(emp.EmpNo) ? "I" : "U";
            return _empDao.SaveEmp(emp);
        }

        public int DeleteEmp(string empNo) => _empDao.DeleteEmp(empNo);
    }

    public class DeptService
    {
        private readonly HrDbContext _db = new HrDbContext();

        public List<Dept> GetDeptList()
        {
            if (AppConfig.UseMock) return MockData.Depts;
            return _db.Depts.Where(d => d.UseYn == "Y").OrderBy(d => d.SortOrd).ToList();
        }

        public void AddDept(Dept dept)
        {
            if (AppConfig.UseMock) { MockData.Depts.Add(dept); return; }
            _db.Depts.Add(dept);
            _db.SaveChanges();
        }

        public void RenameDept(string deptCd, string deptNm)
        {
            if (AppConfig.UseMock) return;
            var dept = _db.Depts.Find(deptCd);
            dept.DeptNm = deptNm;
            _db.SaveChanges();
        }
    }

    public class AttendService
    {
        private readonly AttendDao _attendDao = new AttendDao();

        public List<Attend> GetAttendList(DateTime from, DateTime to, string attType) => _attendDao.SelectAttendList(from, to, attType);

        public int CloseMonth(string ym) => _attendDao.CloseMonth(ym);
    }
}
