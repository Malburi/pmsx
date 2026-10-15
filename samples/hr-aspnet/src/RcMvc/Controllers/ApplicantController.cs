using System.Web.Mvc;
using RcMvc.Models;
using RcMvc.Services;

namespace RcMvc.Controllers
{
    /// <summary>지원자 관리 (ASP.NET MVC 5) — 관례 라우팅 /{controller}/{action}</summary>
    public class ApplicantController : Controller
    {
        private readonly IApplicantService _service;

        public ApplicantController(IApplicantService service)
        {
            _service = service;
        }

        public ActionResult Index()
        {
            ViewBag.Title = "지원자 관리";
            return View();
        }

        [HttpPost]
        public JsonResult List(ApplicantSearch search)
        {
            return Json(_service.GetApplicants(search));
        }

        public ActionResult Detail(int id)
        {
            return View(_service.GetApplicant(id));
        }

        [HttpPost]
        public JsonResult Save(Applicant model)
        {
            _service.SaveApplicant(model);
            return Json(new { ok = true });
        }

        [HttpPost]
        [Route("applicant/pass/{id}")]
        public ActionResult Pass(int id)
        {
            _service.ChangeStatus(id, "PASS");
            return RedirectToAction("Index");
        }
    }
}
