package kr.sample.hr.dept;

import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.actions.DispatchAction;

public class DeptAction extends DispatchAction {
    public ActionForward list(ActionMapping mapping, ActionForm form, Object request, Object response) {
        return mapping.findForward("list");
    }

    private String helper() {
        return "x";
    }
}
