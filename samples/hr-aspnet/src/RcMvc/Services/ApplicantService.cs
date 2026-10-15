using System.Collections.Generic;
using RcMvc.Models;
using RcMvc.Repositories;

namespace RcMvc.Services
{
    public interface IApplicantService
    {
        IEnumerable<Applicant> GetApplicants(ApplicantSearch search);
        Applicant GetApplicant(int id);
        void SaveApplicant(Applicant model);
        void ChangeStatus(int id, string status);
    }

    public class ApplicantService : IApplicantService
    {
        private readonly ApplicantRepository _repo;

        public ApplicantService(ApplicantRepository repo)
        {
            _repo = repo;
        }

        public IEnumerable<Applicant> GetApplicants(ApplicantSearch search) => _repo.Search(search);

        public Applicant GetApplicant(int id) => _repo.Get(id);

        public void SaveApplicant(Applicant model)
        {
            if (model.ApplicantId == 0)
                _repo.Insert(model);
            else
                _repo.Update(model);
        }

        public void ChangeStatus(int id, string status) => _repo.UpdateStatus(id, status);
    }
}
