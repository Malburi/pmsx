package kr.go.hr.appoint.service.impl;

import java.util.List;
import java.util.Map;

import egovframework.rte.psl.dataaccess.mapper.Mapper;
import kr.go.hr.appoint.service.AppointVO;

@Mapper("appointMapper")
public interface AppointMapper {

    List<AppointVO> selectAppointList(AppointVO searchVO);

    int insertAppoint(AppointVO vo);

    void callApptConfirmProc(Map<String, Object> param);
}
