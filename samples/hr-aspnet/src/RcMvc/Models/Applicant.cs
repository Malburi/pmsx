namespace RcMvc.Models
{
    public class Applicant
    {
        public int ApplicantId { get; set; }
        public string Name { get; set; }
        public string Email { get; set; }
        public string RecruitNo { get; set; }
        public string RecruitTitle { get; set; }
        public string Status { get; set; }
    }

    public class ApplicantSearch
    {
        public string Name { get; set; }
        public string Status { get; set; }
    }
}
