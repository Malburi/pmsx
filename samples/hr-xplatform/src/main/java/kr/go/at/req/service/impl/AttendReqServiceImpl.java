package kr.go.at.req.service.impl;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tobesoft.xplatform.data.DataSet;
import com.tobesoft.xplatform.data.PlatformData;

import kr.go.at.req.service.AttendReqService;

@Service("attendReqService")
public class AttendReqServiceImpl implements AttendReqService {

    @Resource(name = "attendMapper")
    private AttendMapper attendMapper;

    @Override
    public PlatformData selectReqList(DataSet search) {
        PlatformData out = new PlatformData();
        out.addDataSet(attendMapper.selectReqList(search));
        return out;
    }

    @Override
    @Transactional
    public void saveReq(DataSet ds) {
        for (int i = 0; i < ds.getRowCount(); i++) {
            int type = ds.getRowType(i);
            if (type == DataSet.ROW_TYPE_INSERTED) {
                attendMapper.insertReq(ds);
            } else if (type == DataSet.ROW_TYPE_UPDATED) {
                attendMapper.updateReq(ds);
            }
        }
    }

    @Override
    @Transactional
    public void requestApproval(DataSet ds) {
        attendMapper.updateReqStatus(ds);
        attendMapper.insertAppr(ds);
    }
}
