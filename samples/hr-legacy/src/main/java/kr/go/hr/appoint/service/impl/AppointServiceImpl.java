package kr.go.hr.appoint.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import egovframework.rte.fdl.cmmn.EgovAbstractServiceImpl;
import kr.go.hr.appoint.service.AppointService;
import kr.go.hr.appoint.service.AppointVO;

@Service("appointService")
public class AppointServiceImpl extends EgovAbstractServiceImpl implements AppointService {

    @Resource(name = "appointMapper")
    private AppointMapper appointMapper;

    @Override
    public List<AppointVO> selectAppointList(AppointVO searchVO) throws Exception {
        return appointMapper.selectAppointList(searchVO);
    }

    @Override
    @Transactional
    public void insertAppoint(AppointVO vo, String userId) throws Exception {
        vo.setRegId(userId);
        vo.setStatus("R");
        appointMapper.insertAppoint(vo);
    }

    @Override
    @Transactional
    public String confirmAppoint(String apptNo, String userId) throws Exception {
        Map<String, Object> param = new HashMap<String, Object>();
        param.put("apptNo", apptNo);
        param.put("userId", userId);
        appointMapper.callApptConfirmProc(param);
        return (String) param.get("resultCd");
    }
}
