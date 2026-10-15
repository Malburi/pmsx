package kr.go.at.req.service.impl;

import com.tobesoft.xplatform.data.DataSet;

import egovframework.rte.psl.dataaccess.mapper.Mapper;

@Mapper("attendMapper")
public interface AttendMapper {
    DataSet selectReqList(DataSet search);

    int insertReq(DataSet ds);

    int updateReq(DataSet ds);

    int updateReqStatus(DataSet ds);

    int insertAppr(DataSet ds);

    DataSet selectApprList(DataSet cond);

    int approve(DataSet ds);

    int reject(DataSet ds);
}
