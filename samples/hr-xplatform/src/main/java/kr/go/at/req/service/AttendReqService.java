package kr.go.at.req.service;

import com.tobesoft.xplatform.data.DataSet;
import com.tobesoft.xplatform.data.PlatformData;

public interface AttendReqService {
    PlatformData selectReqList(DataSet search);

    void saveReq(DataSet ds);

    void requestApproval(DataSet ds);
}
