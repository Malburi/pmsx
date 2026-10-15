package kr.go.hr.appoint.service;

import java.util.List;

public interface AppointService {

    List<AppointVO> selectAppointList(AppointVO searchVO) throws Exception;

    void insertAppoint(AppointVO vo, String userId) throws Exception;

    String confirmAppoint(String apptNo, String userId) throws Exception;
}
